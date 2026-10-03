package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.provider.Usage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 真实客户端评测的一次记账与判分。客户端线程喂服务端快照,传输线程申请重试额度;
 * 两者经同一个锁串行记账。这里不读写文件、
 * 不接管大脑、也不把里程碑提示给模型。预算拒绝只记待收尾状态,宿主在下一次观察后叫停,
 * 避免在模型或工具回调的半步中重入循环。
 */
public final class LiveEvalRun {
    private static final Set<String> CRITERIA = Set.of("items", "min_health", "min_food", "dragon_kills", "within", "world");
    private static final Set<String> OUTCOMES = Set.of("success", "failed", "budget_exhausted", "infra_error", "cancelled");

    private final JsonObject spec;
    private final JsonObject success;
    private final int maxModelCalls;
    private final double maxSeconds;
    private final long maxTokens;
    private final long successHoldTicks;
    private long successSinceTick = -1;
    private long previousTick = -1;
    private long successHeldTicks;
    private final Map<String, JsonObject> milestones = new LinkedHashMap<>();
    private final JsonArray reached = new JsonArray();
    private final JsonArray events = new JsonArray();
    private final Set<String> reachedIds = new java.util.HashSet<>();
    private Usage usage = Usage.ZERO;
    private int unknownUsageReports;
    private int usageReports;
    private int modelCalls;
    private int deniedModelCalls;
    private int initialDeaths;
    private long startNanos;
    private long latestNanos;
    private boolean started;
    private String status;
    private String reason;
    private String pendingStatus;
    private String pendingReason;
    private Double firstTokenMs;
    private Double firstReplyMs;
    private JsonObject initialSnapshot;
    private JsonObject finalSnapshot;

    public LiveEvalRun(JsonObject spec) {
        if (spec == null) throw invalid("spec is required");
        this.spec = spec.deepCopy();
        if (!Set.of("live-v1", "survival-v1").contains(text(spec, "evaluation_version"))) {
            throw invalid("unknown evaluation_version");
        }
        if (spec.has("world_observer") && !"sustainable-survival-v1".equals(text(spec, "world_observer"))) {
            throw invalid("unknown world_observer");
        }
        successHoldTicks = spec.has("success_hold_ticks") ? integer(spec, "success_hold_ticks", 1, Long.MAX_VALUE) : 0;
        text(spec, "case_id");
        text(spec, "prompt");
        integer(spec, "seed", Long.MIN_VALUE, Long.MAX_VALUE);
        if (!Set.of("flat", "normal").contains(text(spec, "world_preset"))) {
            throw invalid("world_preset must be flat or normal");
        }
        object(spec, "setup");
        success = object(this.spec, "success");
        validateCriterion(success);
        JsonObject budget = object(spec, "budget");
        maxModelCalls = (int) integer(budget, "max_model_calls", 1, Integer.MAX_VALUE);
        maxSeconds = number(budget, "max_seconds");
        if (maxSeconds <= 0) throw invalid("max_seconds must be positive");
        maxTokens = integer(budget, "max_tokens", 1, Long.MAX_VALUE);
        if (spec.has("milestones")) {
            for (JsonElement element : array(spec, "milestones")) {
                if (!element.isJsonObject()) throw invalid("milestone must be an object");
                JsonObject milestone = element.getAsJsonObject();
                String id = text(milestone, "id");
                JsonObject criterion = object(milestone, "condition");
                validateCriterion(criterion);
                if (milestones.putIfAbsent(id, criterion.deepCopy()) != null) throw invalid("duplicate milestone id: " + id);
            }
        }
    }

    public synchronized void start(long nowNanos, JsonObject snapshot) {
        if (started) throw new IllegalStateException("evaluation already started");
        validateSnapshot(snapshot);
        started = true;
        startNanos = latestNanos = nowNanos;
        initialDeaths = (int) integer(snapshot, "deaths", 0, Integer.MAX_VALUE);
        initialSnapshot = snapshot.deepCopy();
        event("started", snapshot, nowNanos);
        observe(nowNanos, snapshot);
    }

    /** 包括重试、目标判定与压缩在内,每次真实发送前都经这个入口。第 N 次请求仍可正常结算。 */
    public synchronized boolean tryModelCall(long nowNanos) {
        requireStarted();
        if (finished()) return false;
        latestNanos = Math.max(latestNanos, nowNanos);
        if (!withinBudget(nowNanos)) return false;
        if (usage.total() >= maxTokens) return pending("budget_exhausted", "max_tokens");
        if (modelCalls >= maxModelCalls) {
            deniedModelCalls++;
            return pending("budget_exhausted", "max_model_calls");
        }
        modelCalls++;
        JsonObject data = new JsonObject();
        data.addProperty("attempt", modelCalls);
        event("model_call", data, nowNanos);
        return true;
    }

    /** 不因第 N 次请求已占满额度而拒绝它派出的工具;时间与已报 token 超额仍会拒绝。 */
    public synchronized boolean beforeTool(long nowNanos) {
        requireStarted();
        if (finished()) return false;
        latestNanos = Math.max(latestNanos, nowNanos);
        return withinBudget(nowNanos);
    }

    /** ZERO 也代表供应商未报用量,不能当作免费调用。实际金额没有定价来源,始终不猜。 */
    public synchronized void used(Usage used) {
        requireStarted();
        if (finished()) return;
        usageReports++;
        if (used == null || used.total() == 0 || used.input() < 0 || used.output() < 0
                || used.cacheRead() < 0 || used.cacheWrite() < 0) {
            unknownUsageReports++;
            pending("infra_error", "model_usage_unavailable");
            return;
        }
        try {
            Usage next = new Usage(Math.addExact(usage.input(), used.input()), Math.addExact(usage.output(), used.output()),
                    Math.addExact(usage.cacheRead(), used.cacheRead()), Math.addExact(usage.cacheWrite(), used.cacheWrite()));
            Math.addExact(Math.addExact(next.input(), next.output()), Math.addExact(next.cacheRead(), next.cacheWrite()));
            usage = next;
        } catch (ArithmeticException ex) {
            unknownUsageReports++;
            pending("infra_error", "model_usage_overflow");
        }
        if (usage.total() > maxTokens) pending("budget_exhausted", "max_tokens");
    }

    public synchronized void observe(long nowNanos, JsonObject snapshot) {
        requireStarted();
        if (finished()) return;
        latestNanos = Math.max(latestNanos, nowNanos);
        try {
            validateSnapshot(snapshot);
        } catch (IllegalArgumentException ex) {
            finish("infra_error", "invalid_snapshot: " + ex.getMessage(), nowNanos);
            return;
        }
        finalSnapshot = snapshot.deepCopy();
        event("snapshot", snapshot, nowNanos);
        if (!snapshot.get("alive").getAsBoolean() || snapshot.get("deaths").getAsInt() > initialDeaths) {
            successHeldTicks = 0;
            finish("failed", "companion_died", nowNanos);
            return;
        }
        if (seconds(nowNanos) >= maxSeconds) {
            finish("budget_exhausted", "max_seconds", nowNanos);
            return;
        }
        if (usage.total() > maxTokens) {
            finish("budget_exhausted", "max_tokens", nowNanos);
            return;
        }
        for (Map.Entry<String, JsonObject> milestone : milestones.entrySet()) {
            if (!reachedIds.contains(milestone.getKey()) && met(milestone.getValue(), snapshot)) {
                reachedIds.add(milestone.getKey());
                JsonObject record = new JsonObject();
                record.addProperty("id", milestone.getKey());
                record.addProperty("elapsed_ms", milliseconds(nowNanos));
                record.add("snapshot", snapshot.deepCopy());
                reached.add(record);
            }
        }
        // 最后一次已获准调用可以达成目标;拒绝其后多余的模型请求不会抹掉这份世界证据。
        boolean successMet = met(success, snapshot);
        if (successHoldTicks > 0) {
            long tick = integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
            if (previousTick >= 0 && tick < previousTick) {
                finish("infra_error", "game_tick_moved_backwards", nowNanos);
                return;
            }
            // 观察中断超过一秒游戏时间就重新计时,暂停游戏不能贡献存活时长。
            if (!successMet || (previousTick >= 0 && tick - previousTick > 20)) successSinceTick = -1;
            if (successMet && successSinceTick < 0) successSinceTick = tick;
            successHeldTicks = successSinceTick < 0 ? 0 : tick - successSinceTick;
            previousTick = tick;
        }
        if (successMet && successHeldTicks >= successHoldTicks) {
            finish("success", "authoritative_success_criterion_met", nowNanos);
        } else if (unknownUsageReports > 0 || "infra_error".equals(pendingStatus)) {
            finish("infra_error", pendingReason, nowNanos);
        } else if (pendingStatus != null) {
            finish(pendingStatus, pendingReason, nowNanos);
        }
    }

    /** 第一段可见文字与完整回复分别记延迟,不据此声称回复正确。 */
    public synchronized void event(String type, JsonObject data, long nowNanos) {
        requireStarted();
        if (finished()) return;
        if (type == null || type.isBlank()) throw invalid("event type is required");
        latestNanos = Math.max(latestNanos, nowNanos);
        if (firstTokenMs == null && "first_text_delta".equals(type)) {
            firstTokenMs = milliseconds(nowNanos);
        }
        if (firstReplyMs == null && "assistant".equals(type)) {
            firstReplyMs = milliseconds(nowNanos);
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("elapsed_ms", milliseconds(nowNanos));
        event.add("data", data == null ? new JsonObject() : data.deepCopy());
        events.add(event);
    }

    public synchronized void finish(String status, String reason, long nowNanos) {
        if (finished()) return;
        if (!OUTCOMES.contains(status)) throw invalid("unknown outcome: " + status);
        if (!started) {
            if ("success".equals(status)) throw invalid("success requires a world observation");
            started = true;
            startNanos = latestNanos = nowNanos;
        }
        if ("success".equals(status) && (finalSnapshot == null || !met(success, finalSnapshot)
                || !finalSnapshot.get("alive").getAsBoolean() || finalSnapshot.get("deaths").getAsInt() > initialDeaths
                || seconds(nowNanos) >= maxSeconds || usage.total() > maxTokens || successHeldTicks < successHoldTicks)) {
            throw invalid("success requires living, in-budget authoritative evidence");
        }
        JsonObject data = new JsonObject();
        data.addProperty("status", status);
        data.addProperty("reason", reason);
        event("finished", data, nowNanos);
        this.status = status;
        this.reason = reason;
    }

    public synchronized boolean finished() { return status != null; }

    public synchronized int modelCalls() { return modelCalls; }

    public synchronized JsonArray trace() { return events.deepCopy(); }

    /** 增量落盘只复制尚未写出的事件,不随运行时长反复复制整份轨迹。 */
    public synchronized JsonArray traceFrom(int offset) {
        if (offset < 0 || offset > events.size()) throw invalid("trace offset is outside recorded events");
        JsonArray tail = new JsonArray();
        for (int i = offset; i < events.size(); i++) tail.add(events.get(i).deepCopy());
        return tail;
    }

    public synchronized JsonObject report() {
        JsonObject report = new JsonObject();
        report.add("evaluation_version", spec.get("evaluation_version").deepCopy());
        report.add("case_id", spec.get("case_id").deepCopy());
        report.add("spec", spec.deepCopy());
        report.addProperty("status", status == null ? "running" : status);
        report.addProperty("reason", reason);
        report.addProperty("elapsed_ms", started ? milliseconds(latestNanos) : 0);
        if (successHoldTicks > 0) report.addProperty("success_held_ticks", successHeldTicks);
        report.addProperty("model_calls", modelCalls);
        report.addProperty("denied_model_calls", deniedModelCalls);
        report.addProperty("usage_reports", usageReports);
        report.addProperty("unknown_usage_reports", unknownUsageReports);
        report.addProperty("usage_complete", unknownUsageReports == 0 && usageReports == modelCalls);
        JsonObject tokens = new JsonObject();
        tokens.addProperty("input", usage.input());
        tokens.addProperty("output", usage.output());
        tokens.addProperty("cache_read", usage.cacheRead());
        tokens.addProperty("cache_write", usage.cacheWrite());
        tokens.addProperty("total", usage.total());
        report.add("tokens", tokens);
        report.add("cost", JsonNull.INSTANCE);
        report.add("first_token_ms", firstTokenMs == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(firstTokenMs));
        report.add("first_reply_ms", firstReplyMs == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(firstReplyMs));
        report.add("correct_response_ms", JsonNull.INSTANCE);
        report.addProperty("response_correctness", "not_measured");
        report.add("milestones", reached.deepCopy());
        report.add("initial_snapshot", initialSnapshot == null ? JsonNull.INSTANCE : initialSnapshot.deepCopy());
        report.add("final_snapshot", finalSnapshot == null ? JsonNull.INSTANCE : finalSnapshot.deepCopy());
        return report;
    }

    private boolean withinBudget(long nowNanos) {
        if (finished() || pendingStatus != null) return false;
        if (seconds(nowNanos) >= maxSeconds) return pending("budget_exhausted", "max_seconds");
        if (usage.total() > maxTokens) return pending("budget_exhausted", "max_tokens");
        return true;
    }

    private boolean pending(String status, String reason) {
        if (pendingStatus == null || "infra_error".equals(status)) {
            pendingStatus = status;
            pendingReason = reason;
        }
        return false;
    }

    private void validateSnapshot(JsonObject snapshot) {
        if (snapshot == null) throw invalid("snapshot is required");
        if (!snapshot.has("alive") || !snapshot.get("alive").isJsonPrimitive()
                || !snapshot.getAsJsonPrimitive("alive").isBoolean()) throw invalid("snapshot.alive must be boolean");
        integer(snapshot, "deaths", 0, Integer.MAX_VALUE);
        if (!snapshot.get("alive").getAsBoolean()
                || (started && snapshot.get("deaths").getAsInt() > initialDeaths)) return;
        if (successHoldTicks > 0) integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        validateEvidence(success, snapshot);
        for (JsonObject condition : milestones.values()) validateEvidence(condition, snapshot);
    }

    private static void validateEvidence(JsonObject criterion, JsonObject snapshot) {
        if (criterion.has("world")) {
            JsonObject facts = object(snapshot, "world");
            for (var entry : criterion.getAsJsonObject("world").entrySet()) {
                if (entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    JsonElement fact = facts.get(entry.getKey());
                    if (fact == null || !fact.isJsonPrimitive() || !fact.getAsJsonPrimitive().isBoolean()) {
                        throw invalid("world." + entry.getKey() + " must be boolean");
                    }
                } else if (number(facts, entry.getKey()) < 0) {
                    throw invalid("world." + entry.getKey() + " must be nonnegative");
                }
            }
        }
        if (criterion.has("items")) {
            JsonObject items = object(snapshot, "inventory");
            for (String key : items.keySet()) integer(items, key, 0, Integer.MAX_VALUE);
        }
        if (criterion.has("min_health")) number(snapshot, "health");
        if (criterion.has("min_food")) number(snapshot, "food");
        if (criterion.has("dragon_kills")) integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE);
        if (criterion.has("within")) position(snapshot);
    }

    private static boolean met(JsonObject criterion, JsonObject snapshot) {
        if (criterion.has("world")) {
            JsonObject facts = object(snapshot, "world");
            for (var entry : criterion.getAsJsonObject("world").entrySet()) {
                if (entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    if (!facts.get(entry.getKey()).getAsBoolean()) return false;
                } else if (number(facts, entry.getKey()) < entry.getValue().getAsDouble()) return false;
            }
        }
        if (criterion.has("items")) {
            JsonObject items = snapshot.getAsJsonObject("inventory");
            for (Map.Entry<String, JsonElement> requirement : criterion.getAsJsonObject("items").entrySet()) {
                int have = items.has(requirement.getKey()) ? items.get(requirement.getKey()).getAsInt() : 0;
                if (!Objective.haveItem(requirement.getKey(), requirement.getValue().getAsInt()).met(have, 0)) return false;
            }
        }
        if (criterion.has("min_health") && number(snapshot, "health") < number(criterion, "min_health")) return false;
        if (criterion.has("min_food") && number(snapshot, "food") < number(criterion, "min_food")) return false;
        if (criterion.has("dragon_kills") && snapshot.get("dragon_kills").getAsInt() < criterion.get("dragon_kills").getAsInt()) return false;
        if (criterion.has("within")) {
            JsonObject within = criterion.getAsJsonObject("within");
            double[] target = position(within);
            double[] actual = position(snapshot);
            double distance = Math.hypot(Math.hypot(actual[0] - target[0], actual[1] - target[1]), actual[2] - target[2]);
            if (distance > number(within, "radius")) return false;
        }
        return true;
    }

    private static void validateCriterion(JsonObject criterion) {
        if (criterion.isEmpty()) throw invalid("criterion must be nonempty");
        for (String key : criterion.keySet()) if (!CRITERIA.contains(key)) throw invalid("unknown criterion: " + key);
        if (criterion.has("world")) {
            JsonObject world = object(criterion, "world");
            if (world.isEmpty()) throw invalid("world must be nonempty");
            for (var entry : world.entrySet()) {
                if (entry.getKey().isBlank()) throw invalid("world fact name must be nonempty");
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    if (!value.getAsBoolean()) throw invalid("world boolean criterion must be true");
                } else if (finite(value, "world." + entry.getKey()) < 0) {
                    throw invalid("world numeric criterion must be nonnegative");
                }
            }
        }
        if (criterion.has("items")) {
            JsonObject items = object(criterion, "items");
            if (items.isEmpty()) throw invalid("items must be nonempty");
            for (String id : items.keySet()) {
                if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("invalid item id: " + id);
                integer(items, id, 1, Integer.MAX_VALUE);
            }
        }
        for (String key : Set.of("min_health", "min_food")) {
            if (criterion.has(key) && number(criterion, key) <= 0) throw invalid(key + " must be positive");
        }
        if (criterion.has("dragon_kills")) integer(criterion, "dragon_kills", 1, Integer.MAX_VALUE);
        if (criterion.has("within")) {
            JsonObject within = object(criterion, "within");
            for (String key : within.keySet()) if (!Set.of("position", "radius").contains(key)) throw invalid("unknown within key: " + key);
            position(within);
            if (number(within, "radius") <= 0) throw invalid("radius must be positive");
        }
    }

    private static double[] position(JsonObject object) {
        JsonArray vector = array(object, "position");
        if (vector.size() != 3) throw invalid("position must contain three numbers");
        double[] result = new double[3];
        for (int i = 0; i < result.length; i++) result[i] = finite(vector.get(i), "position[" + i + "]");
        return result;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalid(key + " must be a nonempty string");
        }
        return value.getAsString();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) throw invalid(key + " must be an object");
        return object.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) throw invalid(key + " must be an array");
        return object.getAsJsonArray(key);
    }

    private static long integer(JsonObject object, String key, long min, long max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(key + " must be an integer");
        try {
            long result = new BigDecimal(value.getAsString()).longValueExact();
            if (result < min || result > max) throw invalid(key + " is out of range");
            return result;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw invalid(key + " must be an integer in range");
        }
    }

    private static double number(JsonObject object, String key) { return finite(object.get(key), key); }

    private static double finite(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(key + " must be a number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw invalid(key + " must be finite");
        return result;
    }

    private double seconds(long nowNanos) { return milliseconds(nowNanos) / 1000.0; }

    private double milliseconds(long nowNanos) { return Math.max(0L, nowNanos - startNanos) / 1_000_000.0; }

    private void requireStarted() { if (!started) throw new IllegalStateException("evaluation has not started"); }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
