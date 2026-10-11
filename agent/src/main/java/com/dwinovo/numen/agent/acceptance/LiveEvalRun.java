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
    private final boolean requiresTickHold;
    private final boolean endEntryProtocol;
    private final boolean dragonCombatProtocol;
    private long successSinceTick = -1;
    private long previousTick = -1;
    private long successHeldTicks;
    private final Map<String, JsonObject> milestones = new LinkedHashMap<>();
    private final JsonArray reached = new JsonArray();
    private final JsonArray events = new JsonArray();
    private final Set<String> reachedIds = new java.util.HashSet<>();
    private final Map<String, JsonObject> dragonEvidence = new LinkedHashMap<>();
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
        String version = text(spec, "evaluation_version");
        if (!Set.of("live-v1", "survival-v1", "survival-v2", "expedition-v1", "end-resources-v1", "end-entry-v1", "dragon-combat-v1").contains(version)) {
            throw invalid("unknown evaluation_version");
        }
        requiresTickHold = "end-resources-v1".equals(version);
        endEntryProtocol = "end-entry-v1".equals(version);
        dragonCombatProtocol = "dragon-combat-v1".equals(version);
        String observer = switch (version) {
            case "survival-v2" -> "free-survival-v2";
            case "expedition-v1" -> "expedition-v1";
            case "end-resources-v1" -> "end-resources-v1";
            case "end-entry-v1" -> "end-entry-v1";
            case "dragon-combat-v1" -> "dragon-combat-v1";
            default -> "sustainable-survival-v1";
        };
        if (spec.has("world_observer") && !observer.equals(text(spec, "world_observer"))) {
            throw invalid("unknown world_observer");
        }
        successHoldTicks = spec.has("success_hold_ticks") ? integer(spec, "success_hold_ticks", 1, Long.MAX_VALUE) : 0;
        if (dragonCombatProtocol && successHoldTicks != 200) throw invalid("dragon-combat-v1 requires 200 post-kill survival ticks");
        text(spec, "case_id");
        text(spec, "prompt");
        integer(spec, "seed", Long.MIN_VALUE, Long.MAX_VALUE);
        if (!Set.of("flat", "normal").contains(text(spec, "world_preset"))) {
            throw invalid("world_preset must be flat or normal");
        }
        JsonObject setup = object(spec, "setup");
        if (dragonCombatProtocol && (!"minecraft:the_end".equals(text(setup, "dimension")) || !flag(setup, "native_dragon_battle"))) {
            throw invalid("dragon-combat-v1 requires explicit native End battle initialization");
        }
        if (setup.has("equipment")) {
            if (!requiresTickHold && !endEntryProtocol && !dragonCombatProtocol) throw invalid("equipment initialization requires a supported independent stage");
            JsonObject equipment = object(setup, "equipment");
            for (String slot : equipment.keySet()) {
                if (!Set.of("head", "chest", "legs", "feet", "offhand").contains(slot)) {
                    throw invalid("unknown equipment slot: " + slot);
                }
                if (!text(equipment, slot).matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    throw invalid("invalid equipment item id: " + slot);
                }
            }
        }
        if (setup.has("food_level") || setup.has("saturation")) {
            if (!"survival-v2".equals(version)) throw invalid("food initialization requires survival-v2");
            long foodLevel = setup.has("food_level") ? integer(setup, "food_level", 0, 20) : 20;
            if (setup.has("saturation")) {
                double saturation = number(setup, "saturation");
                if (saturation < 0 || saturation > foodLevel) {
                    throw invalid("saturation must be between 0 and food_level");
                }
            }
        }
        success = object(this.spec, "success");
        validateCriterion(success);
        if (endEntryProtocol && (!success.has("world")
                || !success.getAsJsonObject("world").has("native_end_entry")
                || !success.getAsJsonObject("world").get("native_end_entry").getAsBoolean())) {
            throw invalid("end-entry-v1 success requires native_end_entry evidence");
        }
        if (dragonCombatProtocol && (!success.has("world")
                || !success.getAsJsonObject("world").has("native_dragon_kill")
                || !success.getAsJsonObject("world").get("native_dragon_kill").getAsBoolean())) {
            throw invalid("dragon-combat-v1 success requires native_dragon_kill evidence");
        }
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
        if (snapshot.get("deaths").getAsInt() > initialDeaths) {
            successHeldTicks = 0;
            finish("failed", "companion_died", nowNanos);
            return;
        }
        if (dragonCombatProtocol && dragonDeathObserved(snapshot)) {
            finish("failed", "companion_died", nowNanos);
            return;
        }
        if (dragonCombatProtocol) {
            String problem = dragonCombatProblem(snapshot);
            if (problem != null) {
                finish("infra_error", problem, nowNanos);
                return;
            }
            JsonObject chain = dragonChain(snapshot);
            if (dragonLethalValid(chain)) {
                JsonObject lethal = object(chain, "lethal");
                if (flag(chain, "attribution_ambiguous")
                        || "crystal_nearest_player_fallback".equals(text(lethal, "attribution_origin"))) {
                    finish("infra_error", "attribution_unavailable", nowNanos);
                    return;
                }
                String credited = nullableText(lethal, "credited_actor_uuid");
                if (credited != null && !attributedTo(lethal, "source_actor_uuid", credited)) {
                    finish("infra_error", "attribution_unavailable", nowNanos);
                    return;
                }
                if (credited != null && !text(snapshot, "companion_uuid").equals(credited)) {
                    finish(dragonOtherKillKnown(snapshot) ? "failed" : "infra_error",
                            dragonOtherKillKnown(snapshot) ? "target_killed_by_other" : "dragon_evidence_incomplete", nowNanos);
                    return;
                }
                if (!chain.get("fight_end").isJsonNull() && credited == null) {
                    finish("infra_error", "attribution_unavailable", nowNanos);
                    return;
                }
            }
        }
        if (endEntryProtocol && !snapshot.get("subject_present").getAsBoolean()) {
            finish("infra_error", "observation_unavailable", nowNanos);
            return;
        }
        if (!snapshot.get("alive").getAsBoolean()) {
            successHeldTicks = 0;
            finish("failed", "companion_died", nowNanos);
            return;
        }
        if (endEntryProtocol && flag(object(object(object(snapshot, "world"), "evidence"), "end_entry"),
                "first_death_observed")) {
            successHeldTicks = 0;
            finish("failed", "companion_died", nowNanos);
            return;
        }
        if (endEntryProtocol && !text(snapshot, "companion_uuid").equals(text(initialSnapshot, "companion_uuid"))) {
            finish("infra_error", "companion_identity_changed", nowNanos);
            return;
        }
        if (endEntryProtocol && (!flag(object(snapshot, "world"), "observation_complete")
                || integer(object(object(object(snapshot, "world"), "evidence"), "end_entry"),
                "observation_gaps", 0, Integer.MAX_VALUE) > 0)) {
            finish("infra_error", "observation_incomplete", nowNanos);
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
            if (dragonCombatProtocol) {
                // 用户约定的击杀后存活由服务端逐刻计数;读快照或原生换维不增加保持时间。
                successHeldTicks = successMet ? integer(object(snapshot, "world"), "dragon_post_kill_alive_ticks", 0, Long.MAX_VALUE) : 0;
            } else if (endEntryProtocol) {
                // 入门后存活时长来自同一主体的服务端逐刻观察,客户端不可见不另起一段计时。
                successHeldTicks = successMet ? integer(object(snapshot, "world"), "end_alive_held_ticks", 0, Long.MAX_VALUE) : 0;
            } else {
                // 观察中断超过一秒游戏时间就重新计时,暂停游戏不能贡献存活时长。
                if (!successMet || (previousTick >= 0 && tick - previousTick > 20)) successSinceTick = -1;
                if (successMet && successSinceTick < 0) successSinceTick = tick;
                successHeldTicks = successSinceTick < 0 ? 0 : tick - successSinceTick;
                // 第三阶段由服务端逐 tick 证据约束保持,防止两次客户端快照之间的短暂失效被漏算。
                if (requiresTickHold) {
                    successHeldTicks = Math.min(successHeldTicks,
                            integer(object(snapshot, "world"), "loadout_held_ticks", 0, Long.MAX_VALUE));
                }
            }
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
        if ((endEntryProtocol || dragonCombatProtocol) && (!started || snapshot.get("deaths").getAsInt() <= initialDeaths)) {
            text(snapshot, "companion_uuid");
            flag(snapshot, "subject_present");
            if (!snapshot.get("subject_present").getAsBoolean()) return;
        }
        if (!snapshot.get("alive").getAsBoolean()
                || (started && snapshot.get("deaths").getAsInt() > initialDeaths)) return;
        if (dragonCombatProtocol && dragonDeathObserved(snapshot)) return;
        if (successHoldTicks > 0) integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        if (requiresTickHold) integer(object(snapshot, "world"), "loadout_held_ticks", 0, Long.MAX_VALUE);
        if (endEntryProtocol) validateEndEntrySnapshot(snapshot);
        if (dragonCombatProtocol) validateDragonCombatSnapshot(snapshot);
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

    private boolean met(JsonObject criterion, JsonObject snapshot) {
        if (criterion.has("world")) {
            JsonObject facts = object(snapshot, "world");
            for (var entry : criterion.getAsJsonObject("world").entrySet()) {
                if (entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    if (!facts.get(entry.getKey()).getAsBoolean()) return false;
                } else if (number(facts, entry.getKey()) < entry.getValue().getAsDouble()) return false;
                if (endEntryProtocol && Set.of("portal_reached", "portal_activated_by_companion", "native_end_entry")
                        .contains(entry.getKey()) && !endEntryMet(entry.getKey(), snapshot)) return false;
                if (dragonCombatProtocol && Set.of("dragon_damaged", "dragon_death_started", "native_dragon_kill")
                        .contains(entry.getKey()) && !dragonCombatMet(entry.getKey(), snapshot)) return false;
            }
        }
        if (criterion.has("items")) {
            JsonObject items = snapshot.getAsJsonObject("inventory");
            for (Map.Entry<String, JsonElement> requirement : criterion.getAsJsonObject("items").entrySet()) {
                int have = items.has(requirement.getKey()) ? items.get(requirement.getKey()).getAsInt() : 0;
                if (have < requirement.getValue().getAsInt()) return false;
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

    private static JsonObject dragonChain(JsonObject snapshot) {
        return object(object(object(snapshot, "world"), "evidence"), "dragon_combat");
    }

    /** 原生首次死亡的锁存先于目标成功和缺失主体;身体恢复不能改写这一事实。 */
    private static boolean dragonDeathObserved(JsonObject snapshot) {
        JsonElement world = snapshot.get("world");
        if (world == null || !world.isJsonObject()) return false;
        JsonElement evidence = world.getAsJsonObject().get("evidence");
        if (evidence == null || !evidence.isJsonObject()) return false;
        JsonElement chain = evidence.getAsJsonObject().get("dragon_combat");
        if (chain == null || !chain.isJsonObject()) return false;
        JsonElement death = chain.getAsJsonObject().get("first_death_observed");
        if (death != null && death.isJsonPrimitive() && death.getAsJsonPrimitive().isBoolean() && death.getAsBoolean()) return true;
        JsonElement receipt = chain.getAsJsonObject().get("first_death");
        if (receipt == null || !receipt.isJsonObject()) return false;
        JsonElement source = receipt.getAsJsonObject().get("evidence_source");
        JsonElement actor = receipt.getAsJsonObject().get("subject_uuid");
        return source != null && source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()
                && Set.of("native_companion_death_before_recovery", "server_tick_dead_body").contains(source.getAsString())
                && actor != null && actor.equals(chain.getAsJsonObject().get("subject_uuid"));
    }

    private static void validateDragonCombatSnapshot(JsonObject snapshot) {
        text(snapshot, "dimension");
        integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE);
        JsonObject world = object(snapshot, "world");
        text(world, "subject_uuid");
        for (String key : new String[]{"dragon_combat_ready", "observation_complete", "dragon_damaged", "dragon_death_started", "native_dragon_kill"}) {
            flag(world, key);
        }
        integer(world, "dragon_post_kill_alive_ticks", 0, Long.MAX_VALUE);
        JsonObject chain = dragonChain(snapshot);
        JsonElement firstDeath = chain.get("first_death");
        if (firstDeath == null || (!firstDeath.isJsonNull() && !firstDeath.isJsonObject())) throw invalid("first_death must be an object or null");
        validateDragonIdentity(chain);
        for (String key : new String[]{"first_death_observed", "critical_evidence_complete", "attribution_ambiguous", "calibration_fixture"}) flag(chain, key);
        integer(chain, "observation_gaps", 0, Integer.MAX_VALUE);
        integer(chain, "last_verified_game_tick", 0, Long.MAX_VALUE);
        integer(chain, "post_kill_alive_ticks", 0, Long.MAX_VALUE);
        for (String key : new String[]{"post_kill_hold_start_game_tick", "post_kill_last_verified_game_tick"}) {
            JsonElement value = chain.get(key);
            if (value == null) throw invalid(key + " is required");
            if (!value.isJsonNull()) integer(chain, key, 0, Long.MAX_VALUE);
        }
        for (String key : new String[]{"total_actual_damage", "companion_actual_damage", "minimum_health"}) {
            if (number(chain, key) < 0) throw invalid("dragon_combat." + key + " must be nonnegative");
        }
        if (number(chain, "companion_actual_damage") > number(chain, "total_actual_damage")) {
            throw invalid("companion damage exceeds total damage");
        }
        nullableNumber(chain, "current_health");
        for (String key : new String[]{"initial_battle", "current_battle"}) {
            JsonObject battle = object(chain, key);
            validateDragonIdentity(battle);
            integer(battle, "game_tick", 0, Long.MAX_VALUE);
            for (String flag : new String[]{"fight_bound", "needs_state_scanning", "dragon_killed", "previously_killed", "is_respawning",
                    "target_present", "target_no_ai", "target_invulnerable", "calibration_fixture"}) flag(battle, flag);
            number(battle, "target_max_health");
            nullableNumber(battle, "target_health");
            integer(battle, "crystals_alive", 0, Integer.MAX_VALUE);
            integer(battle, "dragon_kills", 0, Integer.MAX_VALUE);
            text(battle, "phase");
            integer(battle, "death_animation_ticks", 0, Integer.MAX_VALUE);
        }
        JsonObject initial = object(chain, "initial_battle");
        text(initial, "evidence_source");
        for (JsonElement value : array(initial, "crystals")) {
            if (!value.isJsonObject()) throw invalid("initial crystal must be an object");
            JsonObject crystal = value.getAsJsonObject();
            text(crystal, "uuid");
            position(crystal);
            flag(crystal, "invulnerable");
            flag(crystal, "alive");
        }
        JsonObject current = object(chain, "current_battle");
        text(current, "phase");
        integer(current, "death_animation_ticks", 0, Integer.MAX_VALUE);
        for (String key : new String[]{"first_damage", "first_companion_damage", "lethal", "kill_credit", "kill_statistic", "fight_end"}) {
            JsonElement value = chain.get(key);
            if (value == null) throw invalid("dragon_combat." + key + " is required");
            if (value.isJsonNull()) continue;
            JsonObject event = object(chain, key);
            validateDragonIdentity(event);
            integer(event, "game_tick", 0, Long.MAX_VALUE);
            text(event, "evidence_source");
            if (key.endsWith("damage")) {
                validateDragonDamage(event);
            } else if ("lethal".equals(key)) {
                number(event, "health");
                integer(event, "damage_event_id", 1, Long.MAX_VALUE);
                validateDragonHealthLoss(event);
                JsonObject fatal = object(event, "fatal_damage");
                validateDragonIdentity(fatal);
                validateDragonDamage(fatal);
                integer(fatal, "game_tick", 0, Long.MAX_VALUE);
                text(fatal, "evidence_source");
                text(event, "damage_type_message_id");
                text(event, "attribution_origin");
                for (String actor : new String[]{"source_actor_uuid", "direct_entity_uuid", "credited_actor_uuid", "provenance_actor_uuid"}) nullableText(event, actor);
            } else if ("kill_credit".equals(key)) {
                text(event, "actor_uuid");
                text(event, "damage_type_message_id");
                nullableText(event, "source_actor_uuid");
                nullableText(event, "direct_entity_uuid");
            } else if ("kill_statistic".equals(key)) {
                text(event, "actor_uuid");
                integer(event, "before", 0, Integer.MAX_VALUE);
                integer(event, "after", 0, Integer.MAX_VALUE);
            } else {
                flag(event, "dragon_killed");
                flag(event, "previously_killed");
                integer(event, "death_animation_ticks", 0, Integer.MAX_VALUE);
            }
        }
    }

    private static void validateDragonIdentity(JsonObject value) {
        for (String key : new String[]{"subject_uuid", "target_uuid", "fight_id", "dimension"}) text(value, key);
        JsonArray origin = array(value, "origin");
        if (origin.size() != 3) throw invalid("dragon battle origin must contain three coordinates");
        StringBuilder expectedId = new StringBuilder(text(value, "dimension"));
        for (JsonElement coordinate : origin) {
            double number = finite(coordinate, "origin");
            if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid("dragon battle origin must be integral");
            expectedId.append(':').append((long) number);
        }
        expectedId.append(':').append(text(value, "target_uuid"));
        if (!expectedId.toString().equals(text(value, "fight_id"))) throw invalid("fight_id must bind its dimension, origin and target_uuid");
    }

    private static void validateDragonHealthLoss(JsonObject event) {
        for (String key : new String[]{"health_before", "health_after", "actual_health_loss"}) {
            if (number(event, key) < 0) throw invalid("dragon damage health values must be nonnegative");
        }
    }

    private static void validateDragonDamage(JsonObject event) {
        integer(event, "event_id", 1, Long.MAX_VALUE);
        validateDragonHealthLoss(event);
        nullableText(event, "actor_uuid");
        nullableText(event, "provenance_actor_uuid");
        nullableText(event, "source_actor_uuid");
        nullableText(event, "direct_entity_uuid");
        text(event, "damage_type_message_id");
        flag(event, "attribution_reliable");
        text(event, "attribution_origin");
    }

    private String dragonCombatProblem(JsonObject snapshot) {
        if (!flag(snapshot, "subject_present")) return "observation_unavailable";
        if (!flag(snapshot, "alive")) return null;
        JsonObject world = object(snapshot, "world");
        JsonObject chain = dragonChain(snapshot);
        long tick = integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        if (!text(snapshot, "companion_uuid").equals(text(initialSnapshot, "companion_uuid"))) return "companion_identity_changed";
        if (!sameDragonIdentity(chain, dragonChain(initialSnapshot))
                || !text(snapshot, "companion_uuid").equals(text(chain, "subject_uuid"))
                || !text(chain, "subject_uuid").equals(text(world, "subject_uuid"))) return "dragon_identity_changed";
        if (previousTick >= 0 && tick < previousTick) return "game_tick_moved_backwards";
        previousTick = tick;
        if (!flag(world, "observation_complete") || !flag(chain, "critical_evidence_complete")
                || integer(chain, "observation_gaps", 0, Integer.MAX_VALUE) != 0
                || integer(chain, "last_verified_game_tick", 0, Long.MAX_VALUE) != tick) return "observation_incomplete";
        JsonObject initial = object(chain, "initial_battle");
        if (!flag(world, "dragon_combat_ready") || flag(chain, "calibration_fixture")
                || !initial.equals(object(dragonChain(initialSnapshot), "initial_battle"))
                || !sameDragonIdentity(initial, chain) || !validInitialDragonBattle(initial)
                || integer(initialSnapshot, "dragon_kills", 0, Integer.MAX_VALUE) != 0
                || integer(initialSnapshot, "deaths", 0, Integer.MAX_VALUE) != 0
                || integer(initial, "game_tick", 0, Long.MAX_VALUE) > integer(initialSnapshot, "game_tick", 0, Long.MAX_VALUE)) {
            return "initial_dragon_battle_invalid";
        }
        JsonObject initialChain = dragonChain(initialSnapshot);
        JsonObject initialCurrent = object(initialChain, "current_battle");
        if (!flag(initialCurrent, "target_present") || nullableNumber(initialCurrent, "target_health") == null
                || number(initialCurrent, "target_health") != 200 || flag(initialCurrent, "dragon_killed")
                || text(initialCurrent, "phase").startsWith("Dying")
                || flag(initialCurrent, "previously_killed") || integer(initialCurrent, "death_animation_ticks", 0, Integer.MAX_VALUE) != 0) {
            return "initial_dragon_battle_invalid";
        }
        for (String key : new String[]{"first_damage", "first_companion_damage", "lethal", "kill_credit", "kill_statistic", "fight_end"}) {
            if (!initialChain.get(key).isJsonNull()) return "initial_dragon_battle_invalid";
        }
        JsonObject current = object(chain, "current_battle");
        if (!sameDragonIdentity(current, chain) || integer(current, "game_tick", 0, Long.MAX_VALUE) != tick
                || !flag(current, "fight_bound") || flag(current, "needs_state_scanning") || flag(current, "is_respawning")
                || flag(current, "target_no_ai") || flag(current, "target_invulnerable") || flag(current, "calibration_fixture")
                || number(current, "target_max_health") != 200
                || integer(current, "dragon_kills", 0, Integer.MAX_VALUE) != integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE)) {
            return "observation_incomplete";
        }
        for (String key : new String[]{"first_damage", "first_companion_damage", "lethal", "kill_credit", "kill_statistic", "fight_end"}) {
            if (dragonEvidence.containsKey(key) && !dragonEvidence.get(key).equals(chain.get(key))) return "dragon_evidence_inconsistent";
            if (!chain.get(key).isJsonNull()) {
                JsonObject event = object(chain, key);
                if (!sameDragonIdentity(event, chain) || integer(event, "game_tick", 0, Long.MAX_VALUE) < integer(initialSnapshot, "game_tick", 0, Long.MAX_VALUE)
                        || integer(event, "game_tick", 0, Long.MAX_VALUE) > tick) return "dragon_evidence_inconsistent";
                dragonEvidence.putIfAbsent(key, event.deepCopy());
            }
        }
        if (!dragonDamageReceiptsConsistent(chain)) return "dragon_evidence_inconsistent";
        if (!"minecraft:the_end".equals(text(snapshot, "dimension")) && !dragonTerminalEvidence(snapshot, true)) return "dragon_identity_changed";
        long held = integer(world, "dragon_post_kill_alive_ticks", 0, Long.MAX_VALUE);
        if (held != integer(chain, "post_kill_alive_ticks", 0, Long.MAX_VALUE)) return "dragon_evidence_inconsistent";
        if (held > 0 && (chain.get("fight_end").isJsonNull()
                || chain.get("post_kill_hold_start_game_tick").isJsonNull() || chain.get("post_kill_last_verified_game_tick").isJsonNull()
                || integer(chain, "post_kill_hold_start_game_tick", 0, Long.MAX_VALUE)
                != integer(object(chain, "fight_end"), "game_tick", 0, Long.MAX_VALUE) + 1
                || integer(chain, "post_kill_last_verified_game_tick", 0, Long.MAX_VALUE) != tick
                || held != tick - integer(object(chain, "fight_end"), "game_tick", 0, Long.MAX_VALUE))) return "dragon_evidence_inconsistent";
        if (dragonLethalValid(chain)) {
            JsonObject lethal = object(chain, "lethal");
            String actor = nullableText(lethal, "credited_actor_uuid");
            if (flag(chain, "attribution_ambiguous") || "crystal_nearest_player_fallback".equals(text(lethal, "attribution_origin"))
                    || actor != null && !attributedTo(lethal, "source_actor_uuid", actor)
                    || actor != null && !text(snapshot, "companion_uuid").equals(actor)
                    || actor == null && !chain.get("fight_end").isJsonNull()) return null;
        }
        if (!flag(current, "target_present") && !dragonTerminalEvidence(snapshot, false)) return "observation_unavailable";
        if (flag(current, "dragon_killed") && !dragonTerminalEvidence(snapshot, false)) return "dragon_evidence_incomplete";
        if (integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE) > 0
                && (chain.get("kill_statistic").isJsonNull() || !dragonStatisticValid(object(chain, "kill_statistic"), snapshot))) {
            return "dragon_evidence_inconsistent";
        }
        return null;
    }

    private static boolean validInitialDragonBattle(JsonObject initial) {
        if (!"server_native_dragon_fight_initial_state".equals(text(initial, "evidence_source"))
                || !"minecraft:the_end".equals(text(initial, "dimension")) || !flag(initial, "fight_bound")
                || flag(initial, "needs_state_scanning") || flag(initial, "dragon_killed") || flag(initial, "previously_killed")
                || flag(initial, "is_respawning") || !flag(initial, "target_present") || flag(initial, "target_no_ai")
                || flag(initial, "calibration_fixture")
                || text(initial, "phase").startsWith("Dying") || integer(initial, "death_animation_ticks", 0, Integer.MAX_VALUE) != 0
                || flag(initial, "target_invulnerable") || nullableNumber(initial, "target_health") == null
                || number(initial, "target_health") != 200 || number(initial, "target_max_health") != 200
                || integer(initial, "dragon_kills", 0, Integer.MAX_VALUE) != 0
                || integer(initial, "crystals_alive", 0, Integer.MAX_VALUE) != 10 || array(initial, "crystals").size() != 10) return false;
        Set<String> crystals = new java.util.HashSet<>();
        Set<String> positions = new java.util.HashSet<>();
        for (JsonElement value : array(initial, "crystals")) {
            JsonObject crystal = value.getAsJsonObject();
            if (flag(crystal, "invulnerable") || !flag(crystal, "alive")
                    || !crystals.add(text(crystal, "uuid")) || !positions.add(array(crystal, "position").toString())) return false;
        }
        return true;
    }

    private boolean dragonCombatMet(String fact, JsonObject snapshot) {
        JsonObject chain = dragonChain(snapshot);
        if (!flag(object(snapshot, "world"), "dragon_combat_ready") || !flag(chain, "critical_evidence_complete")
                || flag(chain, "first_death_observed") || integer(chain, "observation_gaps", 0, Integer.MAX_VALUE) != 0) return false;
        if ("dragon_damaged".equals(fact)) {
            if (chain.get("first_companion_damage").isJsonNull()) return false;
            JsonObject damage = object(chain, "first_companion_damage");
            return sameDragonIdentity(damage, chain) && validHealthLoss(damage)
                    && "native_ender_dragon_really_hurt".equals(text(damage, "evidence_source"))
                    && flag(damage, "attribution_reliable")
                    && attributedTo(damage, "actor_uuid", text(snapshot, "companion_uuid"));
        }
        if ("dragon_death_started".equals(fact)) return dragonLethalValid(chain);
        return dragonTerminalEvidence(snapshot, true);
    }

    /** 致死、原生记分、统计和动画结束分别保存,任何一个布尔值都不能代替这条链。 */
    private boolean dragonTerminalEvidence(JsonObject snapshot, boolean requireCompanion) {
        JsonObject chain = dragonChain(snapshot);
        if (!dragonLethalValid(chain) || chain.get("kill_credit").isJsonNull()
                || chain.get("kill_statistic").isJsonNull() || chain.get("fight_end").isJsonNull()) return false;
        JsonObject lethal = object(chain, "lethal");
        JsonObject credit = object(chain, "kill_credit");
        JsonObject statistic = object(chain, "kill_statistic");
        JsonObject ended = object(chain, "fight_end");
        String actor = nullableText(lethal, "credited_actor_uuid");
        if (actor == null || !actor.equals(text(credit, "actor_uuid")) || !actor.equals(text(statistic, "actor_uuid"))
                || requireCompanion && !actor.equals(text(snapshot, "companion_uuid"))
                || flag(chain, "attribution_ambiguous") || !attributedTo(lethal, "source_actor_uuid", actor)
                || !"native_server_player_award_kill_score".equals(text(credit, "evidence_source"))
                || !text(lethal, "damage_type_message_id").equals(text(credit, "damage_type_message_id"))
                || !java.util.Objects.equals(nullableText(lethal, "source_actor_uuid"), nullableText(credit, "source_actor_uuid"))
                || !java.util.Objects.equals(nullableText(lethal, "direct_entity_uuid"), nullableText(credit, "direct_entity_uuid"))
                || !"native_end_dragon_fight_set_dragon_killed".equals(text(ended, "evidence_source"))
                || !flag(ended, "dragon_killed") || !flag(ended, "previously_killed")
                || integer(ended, "death_animation_ticks", 0, Integer.MAX_VALUE) != 200
                || !flag(object(chain, "current_battle"), "dragon_killed")
                || !flag(object(chain, "current_battle"), "previously_killed")) return false;
        long lethalTick = integer(lethal, "game_tick", 0, Long.MAX_VALUE);
        long creditTick = integer(credit, "game_tick", 0, Long.MAX_VALUE);
        long statisticTick = integer(statistic, "game_tick", 0, Long.MAX_VALUE);
        long endTick = integer(ended, "game_tick", 0, Long.MAX_VALUE);
        boolean statisticValid = actor.equals(text(snapshot, "companion_uuid")) ? dragonStatisticValid(statistic, snapshot)
                : "native_player_killed_entity_statistic".equals(text(statistic, "evidence_source"))
                && integer(statistic, "after", 0, Integer.MAX_VALUE) - integer(statistic, "before", 0, Integer.MAX_VALUE) == 1
                && integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE) == 0;
        return lethalTick <= creditTick && lethalTick <= statisticTick && creditTick <= endTick && statisticTick <= endTick
                && endTick - lethalTick >= 199 && statisticValid
                && (!requireCompanion || integer(snapshot, "game_tick", 0, Long.MAX_VALUE) > endTick);
    }

    private boolean dragonOtherKillKnown(JsonObject snapshot) {
        JsonObject chain = dragonChain(snapshot);
        if (!dragonLethalValid(chain) || chain.get("kill_credit").isJsonNull() || flag(chain, "attribution_ambiguous")) return false;
        JsonObject lethal = object(chain, "lethal");
        JsonObject credit = object(chain, "kill_credit");
        String actor = nullableText(lethal, "credited_actor_uuid");
        return actor != null && actor.equals(text(credit, "actor_uuid")) && attributedTo(lethal, "source_actor_uuid", actor)
                && "native_server_player_award_kill_score".equals(text(credit, "evidence_source"))
                && text(lethal, "damage_type_message_id").equals(text(credit, "damage_type_message_id"))
                && java.util.Objects.equals(nullableText(lethal, "source_actor_uuid"), nullableText(credit, "source_actor_uuid"))
                && java.util.Objects.equals(nullableText(lethal, "direct_entity_uuid"), nullableText(credit, "direct_entity_uuid"))
                && integer(lethal, "game_tick", 0, Long.MAX_VALUE) <= integer(credit, "game_tick", 0, Long.MAX_VALUE);
    }

    private static boolean dragonStatisticValid(JsonObject statistic, JsonObject snapshot) {
        return "native_player_killed_entity_statistic".equals(text(statistic, "evidence_source"))
                && integer(statistic, "before", 0, Integer.MAX_VALUE) == 0
                && integer(statistic, "after", 0, Integer.MAX_VALUE) == 1
                && integer(snapshot, "dragon_kills", 0, Integer.MAX_VALUE) == 1;
    }

    private static boolean dragonLethalValid(JsonObject chain) {
        if (chain.get("lethal").isJsonNull()) return false;
        JsonObject lethal = object(chain, "lethal");
        JsonObject fatal = object(lethal, "fatal_damage");
        return sameDragonIdentity(lethal, chain) && "native_living_entity_die".equals(text(lethal, "evidence_source"))
                && sameDragonIdentity(fatal, chain) && "native_ender_dragon_really_hurt".equals(text(fatal, "evidence_source"))
                && integer(fatal, "event_id", 1, Long.MAX_VALUE) == integer(lethal, "damage_event_id", 1, Long.MAX_VALUE)
                && integer(fatal, "game_tick", 0, Long.MAX_VALUE) == integer(lethal, "game_tick", 0, Long.MAX_VALUE)
                && number(fatal, "health_before") == number(lethal, "health_before")
                && number(fatal, "health_after") == number(lethal, "health_after")
                && number(fatal, "actual_health_loss") == number(lethal, "actual_health_loss")
                && text(fatal, "attribution_origin").equals(text(lethal, "attribution_origin"))
                && text(fatal, "damage_type_message_id").equals(text(lethal, "damage_type_message_id"))
                && java.util.Objects.equals(nullableText(fatal, "source_actor_uuid"), nullableText(lethal, "source_actor_uuid"))
                && java.util.Objects.equals(nullableText(fatal, "direct_entity_uuid"), nullableText(lethal, "direct_entity_uuid"))
                && java.util.Objects.equals(nullableText(fatal, "actor_uuid"), nullableText(lethal, "source_actor_uuid"))
                && java.util.Objects.equals(nullableText(fatal, "provenance_actor_uuid"), nullableText(lethal, "provenance_actor_uuid"))
                && number(lethal, "health") == 0 && number(lethal, "health_after") == 0 && validHealthLoss(lethal) && validHealthLoss(fatal);
    }

    private static boolean validHealthLoss(JsonObject event) {
        return number(event, "health_before") > number(event, "health_after")
                && number(event, "actual_health_loss") > 0
                && Math.abs(number(event, "actual_health_loss") - (number(event, "health_before") - number(event, "health_after"))) < 0.0001;
    }

    private static boolean dragonDamageReceiptsConsistent(JsonObject chain) {
        Map<Long, JsonObject> receipts = new LinkedHashMap<>();
        for (String key : new String[]{"first_damage", "first_companion_damage", "lethal"}) {
            if (chain.get(key).isJsonNull()) continue;
            JsonObject event = object(chain, key);
            if ("lethal".equals(key)) event = object(event, "fatal_damage");
            long id = integer(event, "event_id", 1, Long.MAX_VALUE);
            JsonObject previous = receipts.putIfAbsent(id, event);
            if (previous != null && !previous.equals(event)) return false;
        }
        return true;
    }

    private static boolean attributedTo(JsonObject event, String actorKey, String actor) {
        return switch (text(event, "attribution_origin")) {
            case "native_damage_source" -> actor.equals(nullableText(event, actorKey));
            case "crystal_explicit_player" -> actor.equals(nullableText(event, "provenance_actor_uuid"));
            default -> false;
        };
    }

    private static boolean sameDragonIdentity(JsonObject first, JsonObject second) {
        for (String key : new String[]{"subject_uuid", "target_uuid", "fight_id", "dimension"}) {
            if (!text(first, key).equals(text(second, key))) return false;
        }
        return "minecraft:the_end".equals(text(first, "dimension")) && array(first, "origin").equals(array(second, "origin"));
    }

    private static String nullableText(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) throw invalid(key + " is required");
        return value.isJsonNull() ? null : text(object, key);
    }

    private static Double nullableNumber(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) throw invalid(key + " is required");
        return value.isJsonNull() ? null : number(object, key);
    }

    /** 原生入门的三个里程碑各留独立方块快照,不能把不同门或不同主体的事拼接。 */
    private static void validateEndEntrySnapshot(JsonObject snapshot) {
        text(snapshot, "dimension");
        integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        JsonObject world = object(snapshot, "world");
        text(world, "subject_uuid");
        for (String key : new String[]{"portal_reached", "portal_activated_by_companion", "native_end_entry", "observation_complete"}) {
            flag(world, key);
        }
        integer(world, "end_alive_held_ticks", 0, Long.MAX_VALUE);
        JsonObject chain = object(object(world, "evidence"), "end_entry");
        text(chain, "subject_uuid");
        flag(chain, "critical_evidence_complete");
        flag(chain, "native_entry_episode_active");
        flag(chain, "first_death_observed");
        integer(chain, "last_verified_game_tick", 0, Long.MAX_VALUE);
        integer(chain, "observation_gaps", 0, Integer.MAX_VALUE);
        for (String key : new String[]{"arrival", "activation", "entry"}) {
            JsonElement value = chain.get(key);
            if (value == null) throw invalid("end_entry." + key + " is required");
            if (value.isJsonNull()) continue;
            JsonObject event = object(chain, key);
            text(event, "kind");
            text(event, "subject_uuid");
            text(event, "dimension");
            text(event, "portal_id");
            integer(event, "game_tick", 0, Long.MAX_VALUE);
            for (String coordinate : new String[]{"x", "y", "z"}) number(event, coordinate);
            validatePortal(object(event, "portal"));
            if ("activation".equals(key)) {
                text(event, "actor_uuid");
                coordinates(object(event, "frame"));
                flag(event, "native_eye_inserted");
                integer(event, "eye_inventory_delta", Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            if ("entry".equals(key)) {
                text(event, "destination_uuid");
                coordinates(object(event, "source_block"));
                text(event, "from_dimension");
                text(event, "to_dimension");
                text(event, "evidence_source");
            }
        }
        if (!chain.has("portal")) throw invalid("end_entry.portal is required");
        if (!chain.get("portal").isJsonNull()) validatePortal(object(chain, "portal"));
    }

    private static void validatePortal(JsonObject portal) {
        text(portal, "portal_id");
        text(portal, "dimension");
        text(portal, "portal_origin");
        coordinates(portal);
        for (String key : new String[]{"generated_stronghold", "active", "initial_active"}) flag(portal, key);
        integer(portal, "eyes", 0, 12);
        integer(portal, "initial_eyes", 0, 12);
        integer(portal, "companion_eye_insertions", 0, Integer.MAX_VALUE);
        integer(portal, "other_eye_insertions", 0, Integer.MAX_VALUE);
        for (JsonElement value : array(portal, "frames")) {
            if (!value.isJsonObject()) throw invalid("portal frame must be an object");
            JsonObject frame = value.getAsJsonObject();
            coordinates(frame);
            text(frame, "id");
            text(frame, "facing");
            flag(frame, "has_eye");
        }
        for (JsonElement value : array(portal, "portal_blocks")) {
            if (!value.isJsonObject()) throw invalid("portal block must be an object");
            coordinates(value.getAsJsonObject());
            text(value.getAsJsonObject(), "id");
        }
    }

    private boolean endEntryMet(String fact, JsonObject snapshot) {
        JsonObject world = object(snapshot, "world");
        JsonObject chain = object(object(world, "evidence"), "end_entry");
        String uuid = text(snapshot, "companion_uuid");
        long tick = integer(snapshot, "game_tick", 0, Long.MAX_VALUE);
        if (!uuid.equals(text(world, "subject_uuid")) || !uuid.equals(text(chain, "subject_uuid"))
                || !flag(world, "observation_complete") || !flag(chain, "critical_evidence_complete")
                || flag(chain, "first_death_observed")
                || integer(chain, "observation_gaps", 0, Integer.MAX_VALUE) != 0
                || integer(chain, "last_verified_game_tick", 0, Long.MAX_VALUE) != tick
                || chain.get("portal").isJsonNull() || chain.get("arrival").isJsonNull()) return false;
        long initialTick = integer(initialSnapshot, "game_tick", 0, Long.MAX_VALUE);
        JsonObject portal = object(chain, "portal");
        JsonObject arrival = object(chain, "arrival");
        if (!portalState(portal, false) || !entryEvent(arrival, "portal_reached", uuid, portal, false)
                || !text(arrival, "dimension").equals(text(portal, "dimension"))
                || integer(arrival, "game_tick", 0, Long.MAX_VALUE) < initialTick
                || integer(arrival, "game_tick", 0, Long.MAX_VALUE) > tick) return false;
        double distance = Math.hypot(Math.hypot(number(arrival, "x") - number(portal, "x") - 0.5,
                number(arrival, "y") - number(portal, "y") - 0.5), number(arrival, "z") - number(portal, "z") - 0.5);
        if (distance > 6) return false;
        if ("portal_reached".equals(fact)) return true;
        if (chain.get("activation").isJsonNull()) return false;
        JsonObject activation = object(chain, "activation");
        JsonObject activatedPortal = object(activation, "portal");
        if (!entryEvent(activation, "portal_activated", uuid, portal, true)
                || !uuid.equals(text(activation, "actor_uuid"))
                || !text(activation, "dimension").equals(text(portal, "dimension"))
                || !flag(activation, "native_eye_inserted")
                || integer(activation, "eye_inventory_delta", Integer.MIN_VALUE, Integer.MAX_VALUE) != -1
                || flag(activatedPortal, "initial_active")
                || integer(activatedPortal, "initial_eyes", 0, 12) >= 12
                || integer(activatedPortal, "other_eye_insertions", 0, Integer.MAX_VALUE) != 0
                || integer(activatedPortal, "companion_eye_insertions", 0, Integer.MAX_VALUE)
                != 12 - integer(activatedPortal, "initial_eyes", 0, 12)
                || !samePortalBaseline(object(arrival, "portal"), activatedPortal)
                || !samePortalBaseline(portal, activatedPortal)
                || integer(portal, "other_eye_insertions", 0, Integer.MAX_VALUE) != 0
                || integer(portal, "companion_eye_insertions", 0, Integer.MAX_VALUE)
                != integer(activatedPortal, "companion_eye_insertions", 0, Integer.MAX_VALUE)
                || integer(arrival, "game_tick", 0, Long.MAX_VALUE) > integer(activation, "game_tick", 0, Long.MAX_VALUE)
                || integer(activation, "game_tick", 0, Long.MAX_VALUE) > tick) return false;
        JsonObject insertedFrame = object(activation, "frame");
        boolean frameFound = false;
        for (JsonElement value : array(activatedPortal, "frames")) {
            JsonObject frame = value.getAsJsonObject();
            if (sameCoordinates(insertedFrame, frame) && flag(frame, "has_eye")) frameFound = true;
        }
        if (!frameFound) return false;
        if ("portal_activated_by_companion".equals(fact)) return true;
        if (chain.get("entry").isJsonNull()) return false;
        JsonObject entry = object(chain, "entry");
        long entryTick = integer(entry, "game_tick", 0, Long.MAX_VALUE);
        boolean sourceFound = false;
        for (JsonElement value : array(object(entry, "portal"), "portal_blocks")) {
            JsonObject block = value.getAsJsonObject();
            if (sameCoordinates(object(entry, "source_block"), block)
                    && "minecraft:end_portal".equals(text(block, "id"))) sourceFound = true;
        }
        return entryEvent(entry, "native_end_entry", uuid, portal, true)
                && uuid.equals(text(entry, "destination_uuid"))
                && flag(chain, "native_entry_episode_active")
                && sourceFound
                && samePortalBaseline(activatedPortal, object(entry, "portal"))
                && integer(activatedPortal, "companion_eye_insertions", 0, Integer.MAX_VALUE)
                == integer(object(entry, "portal"), "companion_eye_insertions", 0, Integer.MAX_VALUE)
                && integer(object(entry, "portal"), "other_eye_insertions", 0, Integer.MAX_VALUE) == 0
                && "vanilla_handle_portal_change_dimension".equals(text(entry, "evidence_source"))
                && text(portal, "dimension").equals(text(entry, "from_dimension"))
                && "minecraft:the_end".equals(text(entry, "to_dimension"))
                && "minecraft:the_end".equals(text(entry, "dimension"))
                && "minecraft:the_end".equals(text(snapshot, "dimension"))
                && integer(activation, "game_tick", 0, Long.MAX_VALUE) <= entryTick && entryTick <= tick
                && integer(world, "end_alive_held_ticks", 0, Long.MAX_VALUE) <= tick - entryTick + 1;
    }

    private static boolean entryEvent(JsonObject event, String kind, String uuid, JsonObject portal, boolean active) {
        JsonObject eventPortal = object(event, "portal");
        return kind.equals(text(event, "kind")) && uuid.equals(text(event, "subject_uuid"))
                && text(portal, "portal_id").equals(text(event, "portal_id"))
                && text(portal, "portal_id").equals(text(eventPortal, "portal_id")) && portalState(eventPortal, active);
    }

    private static boolean samePortalBaseline(JsonObject first, JsonObject second) {
        return flag(first, "initial_active") == flag(second, "initial_active")
                && integer(first, "initial_eyes", 0, 12) == integer(second, "initial_eyes", 0, 12);
    }

    private static boolean portalState(JsonObject portal, boolean requireActive) {
        int x = (int) integer(portal, "x", Integer.MIN_VALUE, Integer.MAX_VALUE);
        int y = (int) integer(portal, "y", Integer.MIN_VALUE, Integer.MAX_VALUE);
        int z = (int) integer(portal, "z", Integer.MIN_VALUE, Integer.MAX_VALUE);
        String dimension = text(portal, "dimension");
        if (!"minecraft:overworld".equals(dimension) || !flag(portal, "generated_stronghold")
                || !"generated_stronghold".equals(text(portal, "portal_origin"))
                || !(dimension + ":" + x + ":" + y + ":" + z).equals(text(portal, "portal_id"))
                || array(portal, "frames").size() != 12 || array(portal, "portal_blocks").size() != 9) return false;
        Set<String> positions = new java.util.HashSet<>();
        int eyes = 0;
        for (JsonElement value : array(portal, "frames")) {
            JsonObject frame = value.getAsJsonObject();
            long dx = integer(frame, "x", Integer.MIN_VALUE, Integer.MAX_VALUE) - x;
            long dz = integer(frame, "z", Integer.MIN_VALUE, Integer.MAX_VALUE) - z;
            String facing = dx == -2 && Math.abs(dz) <= 1 ? "east" : dx == 2 && Math.abs(dz) <= 1 ? "west"
                    : dz == -2 && Math.abs(dx) <= 1 ? "south" : dz == 2 && Math.abs(dx) <= 1 ? "north" : "";
            if (!"minecraft:end_portal_frame".equals(text(frame, "id")) || facing.isEmpty()
                    || !facing.equals(text(frame, "facing")) || integer(frame, "y", Integer.MIN_VALUE, Integer.MAX_VALUE) != y
                    || !positions.add(dx + ":" + dz)) return false;
            if (flag(frame, "has_eye")) eyes++;
        }
        if (eyes != integer(portal, "eyes", 0, 12)) return false;
        positions.clear();
        for (JsonElement value : array(portal, "portal_blocks")) {
            JsonObject block = value.getAsJsonObject();
            long dx = integer(block, "x", Integer.MIN_VALUE, Integer.MAX_VALUE) - x;
            long dz = integer(block, "z", Integer.MIN_VALUE, Integer.MAX_VALUE) - z;
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || integer(block, "y", Integer.MIN_VALUE, Integer.MAX_VALUE) != y
                    || !positions.add(dx + ":" + dz)
                    || (requireActive && !"minecraft:end_portal".equals(text(block, "id")))) return false;
        }
        return !requireActive || flag(portal, "active") && eyes == 12;
    }

    private static boolean sameCoordinates(JsonObject first, JsonObject second) {
        return integer(first, "x", Integer.MIN_VALUE, Integer.MAX_VALUE) == integer(second, "x", Integer.MIN_VALUE, Integer.MAX_VALUE)
                && integer(first, "y", Integer.MIN_VALUE, Integer.MAX_VALUE) == integer(second, "y", Integer.MIN_VALUE, Integer.MAX_VALUE)
                && integer(first, "z", Integer.MIN_VALUE, Integer.MAX_VALUE) == integer(second, "z", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static void coordinates(JsonObject value) {
        for (String key : new String[]{"x", "y", "z"}) integer(value, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(key + " must be boolean");
        }
        return value.getAsBoolean();
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
