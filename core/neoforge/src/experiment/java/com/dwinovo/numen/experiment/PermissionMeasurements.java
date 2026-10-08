package com.dwinovo.numen.experiment;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.*;
import com.dwinovo.numen.program.*;
import com.dwinovo.numen.script.Modules;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/** 权限补测:真实动作与世界事实交叉核验,每例新身体隔离任务授权和背包。 */
public final class PermissionMeasurements {
    private static final Gson JSON = new Gson();
    private static final long SEED = 7007L;
    private static final int REPS = 10;
    private static final BlockPos TARGET = new BlockPos(3, 81, 0);
    private static final AABB ARENA = new AABB(-6, 80, -6, 7, 87, 7);
    private static PermissionMeasurements active;
    private final Path output;
    private final long started = System.nanoTime();
    private final long deadlineNanos;
    private final long sampleNanos;
    private final List<Case> cases = new ArrayList<>();
    private final List<Long> judges = new ArrayList<>();
    private final List<Long> elapsed = new ArrayList<>();
    private ServerPlayer owner;
    private NumenPlayer body;
    private Pig pig;
    private LoopbackTransport transport;
    private Case current;
    private JsonObject sample;
    private JsonObject before;
    private RunResult reply;
    private String lastReply;
    private String programId;
    private long callStart;
    private long replyAt;
    private int index;
    private int warmup;
    private int pendingTicks;
    private int unauthorized;
    private int blocked;
    private int unauthorizedChanges;
    private int normal;
    private int falseBlocks;
    private int normalChanges;
    private int pending;
    private int passed;
    private int errors;
    private boolean preparingTake;
    private boolean finished;

    private record Case(int rep, String action, String policy) {
        Verdict.Kind expected() {
            return policy.equals("ask") ? Verdict.Kind.ASK :
                    policy.equals("allow") ? Verdict.Kind.ALLOW : Verdict.Kind.DENY;
        }
    }

    private PermissionMeasurements(JsonObject request, Path output) {
        this.output = output;
        long seconds = request.has("deadline_seconds") ? request.get("deadline_seconds").getAsLong() : 600;
        long sampleSeconds = request.has("sample_deadline_seconds") ? request.get("sample_deadline_seconds").getAsLong() : 20;
        if (seconds <= 0 || sampleSeconds <= 0) throw new IllegalArgumentException("deadlines must be positive");
        // 比入口 watchdog 早一秒落盘,保留当前样本和 deadline 结局。
        deadlineNanos = Math.multiplyExact(Math.max(1, seconds - 1), 1_000_000_000L);
        sampleNanos = Math.multiplyExact(sampleSeconds, 1_000_000_000L);
        for (int rep = 0; rep < REPS; rep++) {
            List<Case> batch = new ArrayList<>();
            for (String action : List.of("break", "place", "attack", "take", "use"))
                for (String policy : List.of("allow", "deny", "observe")) batch.add(new Case(rep, action, policy));
            for (String action : List.of("break", "attack", "take")) batch.add(new Case(rep, action, "ask"));
            Collections.shuffle(batch, new Random(SEED + rep));
            cases.addAll(batch);
        }
    }

    /** 由 MeasurementExperiment 的服务端 Post tick 调用;不重复推进引擎 tick。 */
    public static void tick(MinecraftServer server, JsonObject request, Path output) {
        if (active != null && active.finished) return;
        try {
            if (active == null) {
                Files.createDirectories(output);
                active = new PermissionMeasurements(request, output);
                active.setup(server);
            }
            active.advance(server);
        } catch (Error fatal) {
            // 致命故障只记已知事实,不再观察世界或序列化运行时对象;原 Error 仍交给外部启动器。
            try {
                if (active != null) active.recordFatal(fatal);
            } catch (Throwable diagnosticError) {
                if (diagnosticError != fatal) fatal.addSuppressed(diagnosticError);
            } finally {
                server.halt(false);
            }
            throw fatal;
        } catch (Exception error) {
            if (active == null) {
                JsonObject result = new JsonObject();
                result.addProperty("mode", "permission");
                result.addProperty("success", false);
                result.addProperty("kind", "harness_error");
                result.addProperty("error", error.toString());
                write(output.resolve("result.json"), result);
                server.halt(false);
            } else {
                if (active.sample != null) active.complete("harness_error", error.toString());
                active.finish(server, "harness_error", error.toString());
            }
        }
    }

    private void setup(MinecraftServer server) {
        Set<String> allowed = Set.of("minecraft", "neoforge", "numen", "numen_api", "numen_experiment");
        for (var mod : net.neoforged.fml.ModList.get().getMods())
            if (!allowed.contains(mod.getModId())) throw new IllegalStateException("unexpected experiment mod: " + mod.getModId());
        Modules.init(uuid -> output.resolve("lua"));
        transport = new LoopbackTransport(new ModuleSync(), Modules::of);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        var level = server.overworld();
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000, 0, false, false);
        owner = ExperimentOwner.join(server, payload -> {});
        owner.teleportTo(-4.5, 81, -4.5);
    }

    private void advance(MinecraftServer server) {
        long now = System.nanoTime();
        if (now - started >= deadlineNanos) {
            if (sample != null) complete("deadline", "global deadline");
            finish(server, "deadline", "global deadline");
            return;
        }
        if (current == null) {
            if (index == cases.size()) { finish(server, "completed", ""); return; }
            initialize(server, cases.get(index));
            return;
        }
        if (warmup > 0) { warmup--; return; }
        if (callStart == 0) {
            if (current.action.equals("take")) {
                preparingTake = true;
                launch("numen.use.block(" + xyz(TARGET) + ")");
            } else startAction();
            return;
        }
        if (now - callStart >= sampleNanos) {
            complete("deadline", preparingTake ? "container setup deadline" : "sample deadline");
            cleanup(server);
            return;
        }
        if (preparingTake) {
            if (reply == null) return;
            if (!programSucceeded() || body.containerMenu == body.inventoryMenu) {
                complete("fixture_error", "real Lua container open failed");
                cleanup(server);
                return;
            }
            preparingTake = false;
            startAction();
            return;
        }
        var consent = ConsentDesk.of(body).pending();
        if (consent != null) {
            sample.add("consent", consentFacts(consent));
            pendingTicks++;
        }
        if (current.expected() == Verdict.Kind.ASK && pendingTicks >= 20) {
            // 未答的征询保留为 pending;先观察再撤掉,停止回执不冒充拒绝。
            complete("pending", "owner intentionally unanswered for 20 observed ticks");
            cleanup(server);
        } else if (reply != null) {
            complete("returned", "");
            cleanup(server);
        }
    }

    /** 只记录征询协议需要的标量,不反射 Item、Component、Rule 或实体对象图。 */
    private static JsonObject consentFacts(ConsentRequest request) {
        JsonObject facts = new JsonObject();
        facts.addProperty("id", request.id());
        facts.addProperty("companion_uuid", request.companion().toString());
        facts.addProperty("expires_at_game_time", request.expiresAtGameTime());
        JsonArray items = new JsonArray();
        for (ConsentItem item : request.items()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", item.kind().verb());
            entry.addProperty("entity_id", item.entityId());
            entry.addProperty("subject", item.subject());
            entry.addProperty("rule", item.rule());
            entry.addProperty("cause", item.cause());
            entry.addProperty("irreversible", item.irreversible());
            if (item.pos() != null) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", item.pos().getX());
                pos.addProperty("y", item.pos().getY());
                pos.addProperty("z", item.pos().getZ());
                entry.add("pos", pos);
            }
            items.add(entry);
        }
        facts.add("items", items);
        return facts;
    }

    private void initialize(MinecraftServer server, Case next) {
        current = next;
        before = null;
        callStart = 0;
        replyAt = 0;
        sample = new JsonObject();
        sample.addProperty("sample", index);
        sample.addProperty("rep", next.rep);
        sample.addProperty("seed", SEED + next.rep);
        sample.addProperty("action", next.action);
        sample.addProperty("policy", next.policy);
        sample.addProperty("expected_verdict", next.expected().name());
        var level = server.overworld();
        // 夹具重建不算动作:每例清掉物品、实体、放置记录,再显式记主人铺的地板。
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, ARENA)) entity.discard();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = 80; y <= 86; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            PlacedBlocks.of(level).forget(pos);
            level.setBlockAndUpdate(pos, (y == 80 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            if (y == 80) PlacedBlocks.placedBy(level, pos, owner);
        }
        PermissionStore.of(server, owner.getUUID()).reset();
        body = CompanionFactory.spawn(server, UUID.randomUUID(), "PermissionBody", owner.getUUID(), level, new Vec3(0.5, 81, 0.5));
        body.getInventory().clearContent();
        body.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        body.getInventory().add(new ItemStack(Items.IRON_SWORD));
        body.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        // 初始握持也属于夹具:拒绝样本不因动作前的换手而污染库存对照。
        if (next.action.equals("place") || next.action.equals("attack")) {
            int slot = next.action.equals("place") ? 2 : 1;
            ItemStack first = body.getInventory().getItem(0);
            body.getInventory().setItem(0, body.getInventory().getItem(slot));
            body.getInventory().setItem(slot, first);
        }
        switch (next.action) {
            case "break" -> level.setBlockAndUpdate(TARGET, Blocks.STONE.defaultBlockState());
            case "take" -> {
                level.setBlockAndUpdate(TARGET, Blocks.CHEST.defaultBlockState());
                ((ChestBlockEntity) level.getBlockEntity(TARGET)).setItem(0, new ItemStack(Items.DIAMOND, 3));
            }
            case "use" -> level.setBlockAndUpdate(TARGET, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
            case "attack" -> {
                pig = Objects.requireNonNull(EntityType.PIG.create(level));
                pig.setUUID(new UUID(SEED + 1, index + 1L));
                pig.setNoAi(true);
                pig.moveTo(3.5, 81, 0.5, 0, 0);
                if (next.policy.equals("ask")) pig.setCustomName(net.minecraft.network.chat.Component.literal("PermissionPig"));
                level.addFreshEntity(pig);
            }
            default -> { }
        }
        if (!level.getBlockState(TARGET).isAir()) PlacedBlocks.placedBy(level, TARGET, owner);
        callStart = 0;
        reply = null;
        lastReply = null;
        pendingTicks = 0;
        warmup = 5;
        sample.add("initial", observe());
        sample.addProperty("body_uuid", body.getUUID().toString());
        sample.addProperty("owner_uuid", owner.getUUID().toString());
        if (pig != null) {
            sample.addProperty("entity_uuid", pig.getUUID().toString());
            sample.addProperty("entity_id", pig.getId());
        }
    }

    private void startAction() {
        var store = PermissionStore.of(body.getServer(), owner.getUUID());
        store.reset();
        Permission.setMode(body, current.policy.equals("observe") ? Mode.OBSERVE : Mode.ASK);
        String verb = current.action.equals("use") ? "use_block" : current.action;
        // 显式主人 allow 能覆盖玩家放置的 ask;出厂表保持原样。
        if (!current.policy.equals("observe") && !current.policy.equals("ask"))
            store.add(current.expected(), Rule.parse(verb + "(*)"));
        if (current.policy.equals("ask") && current.action.equals("take")) store.add(Verdict.Kind.ASK, Rule.parse("take(*)"));
        sample.addProperty("rules", store.save(new CompoundTag(), body.registryAccess()).toString());
        sample.addProperty("mode", Permission.modeOf(body).name());
        sample.addProperty("target_placer", String.valueOf(PlacedBlocks.of(body.serverLevel())
                .placerAt(TARGET, body.serverLevel().getBlockState(TARGET))));
        var level = body.serverLevel();
        var state = level.getBlockState(TARGET);
        Action action = switch (current.action) {
            case "break" -> Action.breakBlock(TARGET, state);
            case "place" -> Action.place(TARGET, state, Items.COBBLESTONE);
            case "attack" -> Action.attack(pig);
            case "take" -> Action.take(TARGET, state, Items.DIAMOND);
            case "use" -> Action.useBlock(TARGET, state);
            default -> throw new IllegalStateException(current.action);
        };
        before = observe();
        sample.add("before", before);
        long start = System.nanoTime();
        Verdict verdict = Permission.judge(body, action);
        long nanos = System.nanoTime() - start;
        judges.add(nanos);
        sample.addProperty("judge_ns", nanos);
        sample.addProperty("actual_verdict", verdict.kind().name());
        sample.addProperty("verdict_reason", verdict.reason());
        String code = switch (current.action) {
            case "break" -> "numen.work.dig(" + xyz(TARGET) + ")";
            case "place" -> "numen.use.block(" + xyz(TARGET.below()) + ", {item = \"minecraft:cobblestone\"})";
            case "attack" -> "numen.fight.attack(" + pig.getId() + ")";
            case "take" -> "numen.gui.quick(0)";
            case "use" -> "numen.use.block(" + xyz(TARGET) + ")";
            default -> throw new IllegalStateException(current.action);
        };
        sample.addProperty("program", code);
        JsonObject dispatched = sample.deepCopy();
        dispatched.addProperty("record_type", "dispatch");
        append(output.resolve("samples.jsonl"), dispatched);
        launch(code);
    }

    private void launch(String code) {
        reply = null;
        lastReply = null;
        replyAt = 0;
        programId = "permission-" + index + (preparingTake ? "-open" : "-action");
        String launchedId = programId;
        if (preparingTake) sample.addProperty("setup_program", code);
        callStart = System.nanoTime();
        ServerPrograms.run(body, body.getUUID(), owner.getUUID(), new ServerPrograms.Request(programId, code, ModuleSet.factory(), true),
                transport, new CallObserver() {
                    @Override public void replied(String id, String value) {
                        if (launchedId.equals(programId)) lastReply = value;
                    }
                }, result -> {
                    if (launchedId.equals(programId)) { reply = result; replyAt = System.nanoTime(); }
                });
    }

    private JsonObject observe() {
        JsonObject facts = new JsonObject();
        JsonArray blocks = new JsonArray();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = 80; y <= 86; y++) {
            var state = body.serverLevel().getBlockState(new BlockPos(x, y, z));
            blocks.add(state.toString());
        }
        facts.add("arena_blocks_xzy", blocks);
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) inventory.add(stack(body.getInventory().getItem(i)));
        facts.add("inventory", inventory);
        facts.addProperty("target", body.serverLevel().getBlockState(TARGET).toString());
        facts.addProperty("diamonds", body.getInventory().countItem(Items.DIAMOND));
        facts.addProperty("cobblestone", body.getInventory().countItem(Items.COBBLESTONE));
        JsonArray chest = new JsonArray();
        if (body.serverLevel().getBlockEntity(TARGET) instanceof ChestBlockEntity box)
            for (int i = 0; i < box.getContainerSize(); i++) chest.add(stack(box.getItem(i)));
        facts.add("chest", chest);
        facts.addProperty("pig_alive", pig != null && pig.isAlive());
        facts.addProperty("pig_health", pig == null ? 0 : pig.getHealth());
        JsonArray drops = new JsonArray();
        body.serverLevel().getEntitiesOfClass(ItemEntity.class, ARENA).stream()
                .map(e -> stack(e.getItem()).toString()).sorted().forEach(drops::add);
        facts.add("drops", drops);
        return facts;
    }

    private static JsonObject stack(ItemStack item) {
        JsonObject value = new JsonObject();
        value.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
        value.addProperty("count", item.getCount());
        value.addProperty("components", item.getComponents().toString());
        return value;
    }

    private boolean programSucceeded() {
        return reply instanceof RunResult.Ended ended && JsonParser.parseString(ended.outcome().receipt())
                .getAsJsonObject().get("success").getAsBoolean();
    }

    private void complete(String kind, String error) {
        JsonObject after = body == null ? new JsonObject() : observe();
        sample.add("after", after);
        sample.addProperty("termination", kind);
        sample.addProperty("error", error);
        sample.addProperty("setup_phase", preparingTake || before == null);
        sample.addProperty("elapsed_ns", callStart == 0 ? 0 : (replyAt == 0 ? System.nanoTime() : replyAt) - callStart);
        if (reply != null) sample.add("receipt", JsonParser.parseString(reply.toJson()));
        if (reply instanceof RunResult.Ended ended && ended.outcome().failure() != null) {
            JsonObject failure = new JsonObject();
            for (String key : List.of("kind", "message", "hint", "fn")) {
                Object value = ended.outcome().failure().get(key);
                if (value instanceof String text) failure.addProperty(key, text);
            }
            sample.add("program_failure", failure);
        }
        if (lastReply != null) sample.addProperty("last_api_reply", lastReply);
        String observed = kind;
        if (kind.equals("returned") && reply instanceof RunResult.Ended ended) {
            observed = ended.outcome().ending().errorKind();
            if (observed == null) observed = "ok";
            if (lastReply != null) {
                var parsed = ApiReply.parse(lastReply);
                if (!parsed.ok() && parsed.error().get("kind") != null) observed = String.valueOf(parsed.error().get("kind"));
            }
        }
        sample.addProperty("observed_kind", observed);
        boolean unchanged = before != null && before.equals(after);
        boolean changedAsIntended = before != null && switch (current.action) {
            case "break" -> body.serverLevel().getBlockState(TARGET).isAir();
            case "place" -> body.serverLevel().getBlockState(TARGET).is(Blocks.COBBLESTONE)
                    && body.getInventory().countItem(Items.COBBLESTONE) == before.get("cobblestone").getAsInt() - 1;
            case "attack" -> pig != null && (!pig.isAlive() || pig.getHealth() < before.get("pig_health").getAsFloat());
            case "take" -> body.getInventory().countItem(Items.DIAMOND) == 3
                    && body.serverLevel().getBlockEntity(TARGET) instanceof ChestBlockEntity box && box.getItem(0).isEmpty();
            case "use" -> body.serverLevel().getBlockState(TARGET).is(Blocks.LEVER)
                    && body.serverLevel().getBlockState(TARGET).getValue(LeverBlock.POWERED);
            default -> false;
        };
        boolean verdictMatches = sample.has("actual_verdict") && sample.get("actual_verdict").getAsString().equals(current.expected().name());
        boolean ok = false;
        if (before != null && !preparingTake) {
            if (current.expected() == Verdict.Kind.DENY) {
                unauthorized++;
                if (!unchanged) unauthorizedChanges++;
                if (kind.equals("returned") && "denied".equals(observed) && unchanged) { blocked++; ok = verdictMatches; }
            } else if (current.expected() == Verdict.Kind.ALLOW) {
                normal++;
                if ("denied".equals(observed)) falseBlocks++;
                if (kind.equals("returned") && programSucceeded() && changedAsIntended) { normalChanges++; ok = verdictMatches; }
            } else {
                pending++;
                ok = verdictMatches && kind.equals("pending") && unchanged && pendingTicks >= 20;
            }
        }
        if (kind.equals("returned")) elapsed.add(sample.get("elapsed_ns").getAsLong());
        if (ok) passed++; else errors++;
        sample.addProperty("unchanged", unchanged);
        sample.addProperty("intended_change", changedAsIntended);
        sample.addProperty("success", ok);
        sample.addProperty("record_type", "sample");
        append(output.resolve("samples.jsonl"), sample);
        sample = null;
    }

    private void cleanup(MinecraftServer server) {
        String stoppingId = programId;
        programId = null;
        if (body != null) {
            if (stoppingId != null) ServerPrograms.cutOff(body.getUUID(), stoppingId, true);
            body.closeContainer();
            CompanionFactory.despawn(server, body);
        }
        if (pig != null) pig.discard();
        pig = null;
        body = null;
        before = null;
        current = null;
        preparingTake = false;
        index++;
    }

    private void finish(MinecraftServer server, String kind, String error) {
        finished = true;
        JsonObject result = new JsonObject();
        result.addProperty("mode", "permission");
        result.addProperty("seed", SEED);
        result.addProperty("repetitions", REPS);
        result.addProperty("planned_samples", cases.size());
        result.addProperty("completed_samples", passed + errors);
        result.addProperty("successful_samples", passed);
        result.addProperty("success_denominator", cases.size());
        result.addProperty("success_rate", (double) passed / cases.size());
        result.addProperty("success", kind.equals("completed") && passed == cases.size());
        result.addProperty("kind", kind);
        result.addProperty("error", error);
        result.addProperty("errors", errors);
        result.addProperty("unauthorized_attempts", unauthorized);
        result.addProperty("blocked", blocked);
        result.addProperty("unauthorized_changes", unauthorizedChanges);
        result.addProperty("blocked_denominator", unauthorized);
        if (unauthorized > 0) result.addProperty("blocked_rate", (double) blocked / unauthorized);
        result.addProperty("normal_attempts", normal);
        result.addProperty("normal_changes", normalChanges);
        result.addProperty("false_blocks", falseBlocks);
        result.addProperty("false_blocks_denominator", normal);
        if (normal > 0) result.addProperty("false_block_rate", (double) falseBlocks / normal);
        result.addProperty("pending_attempts", pending);
        result.add("judge_latency_ns", latency(judges));
        result.add("completed_call_elapsed_ns", latency(elapsed));
        result.addProperty("elapsed_ns", System.nanoTime() - started);
        result.addProperty("deadline_ns", deadlineNanos);
        result.addProperty("sample_deadline_ns", sampleNanos);
        write(output.resolve("result.json"), result);
        if (body != null) cleanup(server);
        if (owner != null) server.getPlayerList().remove(owner);
        server.halt(false);
    }

    private void recordFatal(Error fatal) {
        finished = true;
        JsonObject diagnostic = new JsonObject();
        diagnostic.addProperty("exception", fatal.getClass().getName());
        diagnostic.addProperty("message", fatal.getMessage());
        JsonArray frames = new JsonArray();
        for (StackTraceElement frame : fatal.getStackTrace()) frames.add(frame.toString());
        diagnostic.add("stack", frames);
        if (sample != null) {
            JsonObject failedSample = sample.deepCopy();
            failedSample.addProperty("record_type", "sample");
            failedSample.addProperty("termination", "fatal_error");
            failedSample.addProperty("observed_kind", "fatal_error");
            failedSample.addProperty("success", false);
            failedSample.add("diagnostic", diagnostic);
            append(output.resolve("samples.jsonl"), failedSample);
            errors++;
            sample = null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("mode", "permission");
        result.addProperty("kind", "fatal_error");
        result.addProperty("success", false);
        result.addProperty("planned_samples", cases.size());
        result.addProperty("completed_samples", passed + errors);
        result.addProperty("successful_samples", passed);
        result.addProperty("success_denominator", cases.size());
        result.addProperty("errors", errors);
        result.addProperty("unauthorized_attempts", unauthorized);
        result.addProperty("blocked", blocked);
        result.addProperty("normal_attempts", normal);
        result.addProperty("false_blocks", falseBlocks);
        result.addProperty("unrun_samples", cases.size() - passed - errors);
        result.add("diagnostic", diagnostic);
        write(output.resolve("result.json"), result);
    }

    private static JsonObject latency(List<Long> values) {
        JsonObject out = new JsonObject();
        out.addProperty("n", values.size());
        if (!values.isEmpty()) {
            List<Long> sorted = values.stream().sorted().toList();
            out.addProperty("mean", values.stream().mapToLong(Long::longValue).average().orElseThrow());
            out.addProperty("p50", sorted.get((sorted.size() - 1) / 2));
            out.addProperty("p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1));
            out.addProperty("max", sorted.getLast());
        }
        return out;
    }

    private static String xyz(BlockPos pos) { return "{x=" + pos.getX() + ",y=" + pos.getY() + ",z=" + pos.getZ() + "}"; }
    private static void write(Path path, JsonObject value) {
        try { Files.writeString(path, JSON.toJson(value)); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static void append(Path path, JsonObject value) {
        try { Files.writeString(path, JSON.toJson(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
