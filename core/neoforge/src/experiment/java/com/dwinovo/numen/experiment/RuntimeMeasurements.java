package com.dwinovo.numen.experiment;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.llm.*;
import com.dwinovo.numen.core.task.move.*;
import com.dwinovo.numen.entity.*;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.program.*;
import com.dwinovo.numen.task.*;
import com.google.gson.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 真移动/真本能/真 HTTP 的有界观察夹具,不改引擎或本能选择。 */
public final class RuntimeMeasurements {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static RuntimeMeasurements instance;
    private static long preNano, previousPre, processingNano, intervalNano;
    private static String secret = "";
    private final JsonObject request;
    private final Path output;
    private final JsonArray trials = new JsonArray(), phases = new JsonArray(), modelCalls = new JsonArray();
    private final AtomicInteger attempts = new AtomicInteger();
    private ServerPlayer owner;
    private NumenPlayer her;
    private Zombie threat;
    private ObservedMove observed;
    private int trial, stage, moving, quiet, sinceTrigger, age, pair, phaseTicks;
    private long triggerNano, triggerTick, stopNano, stopTick, releaseNano, phaseBegan;
    private Vec3 previous;
    private JsonObject row, phase;
    private JsonArray samples;
    private boolean initialized, finished, baseline = true;
    private CompletableFuture<NumenLlmClient.ChatResult> pending;
    private CancelToken cancel;
    private AtomicReference<JsonObject> usageFrame;
    private JsonObject call;
    private LlmEndpoint endpoint;
    private String programReceipt = "";

    private RuntimeMeasurements(JsonObject request, Path output) { this.request = request; this.output = output; }
    public static void pre() {
        long now = System.nanoTime();
        intervalNano = previousPre == 0 ? 0 : now - previousPre;
        previousPre = preNano = now;
    }
    public static void post() { processingNano = System.nanoTime() - preNano; }
    public static String clean(String text) { return secret.isEmpty() ? text : text.replace(secret, "[redacted]"); }

    public static void tick(MinecraftServer server, JsonObject request, Path output) throws IOException {
        if (instance == null) instance = new RuntimeMeasurements(request, output);
        instance.advance(server);
    }

    private void setup(MinecraftServer server) throws IOException {
        var level = server.overworld();
        server.setDifficulty(Difficulty.NORMAL, true);
        level.setDayTime(18000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        level.setWeatherParameters(24000, 0, false, false);
        for (int x = -40; x <= 80; x++) for (int z = -24; z <= 24; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 80, z), Blocks.STONE.defaultBlockState());
            for (int y = 81; y <= 85; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        owner = ExperimentOwner.join(server, p -> {});
        owner.setInvulnerable(true);
        // 用同一真实执行器,仅观察它被调到 stop/tick 的时刻。
        TaskFactory.register(MoveToTaskRecord.class, (body, record) -> {
            observed = new ObservedMove(new MoveToCompanionTask(body, record));
            return observed;
        });
        JsonObject entry = JsonParser.parseString(Files.readString(Path.of(request.get("providers_file").getAsString())))
                .getAsJsonObject().getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> e.get("id").getAsString().equals(request.get("provider_id").getAsString()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("provider entry not found"));
        secret = field(entry, "api_key");
        endpoint = new LlmEndpoint(field(entry, "provider"), field(entry, "model"), secret,
                field(entry, "base_url"), field(entry, "proxy"), field(entry, "reasoning_effort"));
        if (secret.isBlank() || endpoint.model().isBlank()) throw new IllegalArgumentException("provider is incomplete");
        write("model.json", Map.of("provider", endpoint.provider(), "model", endpoint.model(),
                "reasoning_effort", endpoint.reasoningEffort()));
        initialized = true;
        beginTrial(server);
    }

    private static String field(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private void beginTrial(MinecraftServer server) {
        her = CompanionFactory.spawn(server, UUID.randomUUID(), "RuntimeBody", owner.getUUID(), server.overworld(), new Vec3(.5, 81, .5));
        her.setInvulnerable(true);
        row = new JsonObject();
        row.addProperty("repeat", trial % 10 + 1);
        row.addProperty("test", trial < 10 ? "owner_cancel" : "registered_flee_preemption");
        row.addProperty("policy", trial < 10 ? "CancelTasksPayload.handle(owner)" :
                "normal difficulty; empty melee/ranged loadout; noAI zombie at 3 blocks explicitly targets body; registered flee order 25");
        row.addProperty("registration_count", BrainChains.size());
        row.add("samples", new JsonArray());
        observed = null;
        programReceipt = "";
        stage = moving = quiet = sinceTrigger = age = 0;
        triggerNano = stopNano = releaseNano = 0;
        previous = her.position();
        ServerPrograms.launch(her, "runtime_move", "numen.move.to({x=65,y=81,z=0})", CallObserver.NONE,
                receipt -> programReceipt = clean(receipt));
    }

    private void advance(MinecraftServer server) throws IOException {
        if (finished) return;
        if (!initialized) { setup(server); return; }
        if (trial >= 20) { timing(server); return; }
        age++;
        Vec3 at = her.position();
        double displacement = at.distanceTo(previous);
        double speed = her.getDeltaMovement().horizontalDistance();
        previous = at;
        if (stage == 0) {
            moving = observed != null && observed.ticks > 0 && displacement > .05 ? moving + 1 : 0;
            if (moving >= 5) {
                row.addProperty("stable_moving_ticks", moving);
                row.addProperty("pre_trigger_speed", speed);
                row.addProperty("pre_trigger_displacement", displacement);
                row.addProperty("task_id", CompanionTickDispatcher.currentTaskFor(her.getUUID()).publicId());
                triggerNano = System.nanoTime();
                triggerTick = server.getTickCount();
                stage = 1;
                if (trial < 10) CancelTasksPayload.handle(new CancelTasksPayload(her.getUUID()), owner);
                else {
                    threat = EntityType.ZOMBIE.create(server.overworld());
                    if (threat == null) throw new IllegalStateException("zombie fixture creation failed");
                    threat.setNoAi(true);
                    threat.setInvulnerable(true);
                    threat.moveTo(at.x + 3, 81, at.z + 1, 0, 0);
                    threat.setTarget(her);
                    server.overworld().addFreshEntity(threat);
                }
            } else if (age > 400) endTrial(server, "move_never_stably_started");
            return;
        }
        sinceTrigger++;
        JsonObject sample = new JsonObject();
        sample.addProperty("ticks_since_trigger", server.getTickCount() - triggerTick);
        sample.addProperty("wall_ms", (System.nanoTime() - triggerNano) / 1e6);
        sample.addProperty("speed", speed);
        sample.addProperty("position_delta", displacement);
        sample.addProperty("original_task_ticks", observed.ticks);
        sample.addProperty("holder", holder());
        row.getAsJsonArray("samples").add(sample);
        if (stopNano != 0 && !row.has("stop_ms")) {
            row.addProperty("stop_ms", (stopNano - triggerNano) / 1e6);
            row.addProperty("stop_ticks", stopTick - triggerTick);
            row.addProperty("stop_reason", observed.reason.name());
            row.addProperty("release_ms", (releaseNano - triggerNano) / 1e6);
            row.add("controls_after_stop", observed.heldAfterStop);
        }
        if (trial < 10) {
            quiet = stopNano != 0 && speed < .01 && displacement < .01 ? quiet + 1 : 0;
            if (quiet >= 3) {
                row.addProperty("physical_stop_confirmed_ms", (System.nanoTime() - triggerNano) / 1e6);
                row.addProperty("physical_stop_confirmed_ticks", server.getTickCount() - triggerTick);
                row.addProperty("physical_stop_window_ticks", 3);
                endTrial(server, "measured");
            } else if (sinceTrigger >= 80) endTrial(server, "body_did_not_settle_below_0.01_for_three_ticks");
        } else {
            if (stopNano != 0 && observed.reason == Task.StopReason.PREEMPTED && holder().equals("flee")) {
                row.addProperty("handoff_ms", (System.nanoTime() - triggerNano) / 1e6);
                row.addProperty("handoff_ticks", server.getTickCount() - triggerTick);
                row.addProperty("original_ticks_at_stop", observed.ticksAtStop);
                if (sinceTrigger >= 3) {
                    row.addProperty("original_task_advanced_after_stop", observed.ticks > observed.ticksAtStop);
                    endTrial(server, observed.ticks == observed.ticksAtStop ? "measured" : "original_task_kept_advancing");
                }
            } else if (sinceTrigger >= 80) endTrial(server, "registered_flee_did_not_take_body");
        }
    }

    /** 私有调度状态只读:辨别拿到身体的是现有注册本能,不另造竞价链。 */
    private String holder() {
        try {
            Field brains = CompanionTickDispatcher.class.getDeclaredField("BRAINS");
            brains.setAccessible(true);
            Object brain = ((Map<?, ?>) brains.get(null)).get(her.getUUID());
            if (brain == null) return "none";
            Field holder = brain.getClass().getDeclaredField("holder");
            holder.setAccessible(true);
            Task task = (Task) holder.get(brain);
            return task == null ? "none" : task.name();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("cannot observe scheduler holder", error); }
    }

    private void endTrial(MinecraftServer server, String outcome) throws IOException {
        row.addProperty("outcome", outcome);
        row.addProperty("success", outcome.equals("measured"));
        row.addProperty("receipt", programReceipt);
        if (!row.has("stop_ms")) row.addProperty("not_stopped_reason", outcome);
        trials.add(row);
        write("runtime-progress.json", trials);
        if (threat != null) { threat.discard(); threat = null; }
        CompanionFactory.despawn(server, her);
        trial++;
        if (trial < 20) beginTrial(server);
        else {
            her = CompanionFactory.spawn(server, UUID.randomUUID(), "IdleRuntimeBody", owner.getUUID(), server.overworld(), new Vec3(.5, 81, .5));
            age = 0;
            baseline = true;
        }
    }

    private void startPhase() {
        phaseTicks = 0;
        phaseBegan = System.nanoTime();
        phase = new JsonObject();
        phase.addProperty("pair", pair + 1);
        phase.addProperty("phase", baseline ? "baseline_idle" : "deepseek_request");
        phase.addProperty("observation_ticks", 250);
        phase.addProperty("same_body", her.getUUID().toString());
        samples = new JsonArray();
        phase.add("samples", samples);
        if (!baseline) {
            cancel = new CancelToken();
            cancel.requestPermit(() -> attempts.incrementAndGet() <= 3);
            usageFrame = new AtomicReference<>();
            call = new JsonObject();
            call.addProperty("call", pair + 1);
            call.addProperty("started_nano", phaseBegan);
            pending = NumenLlmClient.forEndpoint(endpoint).chatStreaming(
                    List.of(new ConvoState.Msg.User("For a runtime latency measurement, produce a detailed 3000-word mathematical derivation of the first 100 prime numbers, verifying divisibility for each. Do not call tools.")),
                    List.of(), "You are a mathematical assistant. Complete the requested derivation.", cancel,
                    chunk -> { if (chunk.has("usage") && chunk.get("usage").isJsonObject()) usageFrame.set(chunk.getAsJsonObject("usage").deepCopy()); });
            // 异步线程只记录原始完成时刻,结算仍在服务器线程。
            pending.whenComplete((answer, error) -> callCompletionNano.set(System.nanoTime()));
        }
    }

    private final java.util.concurrent.atomic.AtomicLong callCompletionNano = new java.util.concurrent.atomic.AtomicLong();

    private void timing(MinecraftServer server) throws IOException {
        if (++age <= 100) return;
        if (phase == null) { callCompletionNano.set(0); startPhase(); return; }
        if (phaseTicks < 250) {
            JsonObject sample = new JsonObject();
            sample.addProperty("tick", server.getTickCount());
            sample.addProperty("server_processing_ms", processingNano / 1e6);
            sample.addProperty("wall_tick_interval_ms", intervalNano / 1e6);
            sample.addProperty("request_pending", !baseline && !pending.isDone());
            sample.addProperty("body_idle", CompanionTickDispatcher.currentTaskFor(her.getUUID()) == null);
            sample.addProperty("holder", holder());
            samples.add(sample);
            phaseTicks++;
            if (phaseTicks < 250) return;
            phase.addProperty("phase_wall_ms", (System.nanoTime() - phaseBegan) / 1e6);
            phase.addProperty("pending_ticks", samples.asList().stream().filter(e -> e.getAsJsonObject().get("request_pending").getAsBoolean()).count());
            phase.add("server_processing_ms", statistics(samples, "server_processing_ms", false));
            phase.add("wall_tick_interval_ms", statistics(samples, "wall_tick_interval_ms", false));
            if (!baseline) phase.add("pending_server_processing_ms", statistics(samples, "server_processing_ms", true));
            phases.add(phase);
            write("tick-phases.json", phases);
        }
        if (!baseline) {
            if (!pending.isDone()) {
                if (System.nanoTime() - phaseBegan < 180_000_000_000L) return;
                call.addProperty("outcome", "request_watchdog");
                cancel.cancel();
            } else {
                try {
                    var answer = pending.join();
                    call.addProperty("outcome", "completed");
                    call.add("usage", JSON.toJsonTree(answer.usage()));
                } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException error) {
                    call.addProperty("outcome", "model_error");
                    call.addProperty("error", clean(error.toString()));
                }
            }
            call.addProperty("wall_ms", (callCompletionNano.get() == 0 ? System.nanoTime() - phaseBegan : callCompletionNano.get() - phaseBegan) / 1e6);
            call.add("raw_usage", usageFrame.get() == null ? JsonNull.INSTANCE : usageFrame.get());
            call.addProperty("usage_known", usageFrame.get() != null && usageFrame.get().has("prompt_tokens") && usageFrame.get().has("completion_tokens"));
            modelCalls.add(call);
            write("model-calls.json", modelCalls);
            pair++;
        }
        baseline = !baseline;
        phase = null;
        age = 0;
        if (pair >= 3) finish(server);
    }

    private void finish(MinecraftServer server) throws IOException {
        finished = true;
        JsonObject result = new JsonObject();
        result.addProperty("kind", "runtime_measurements");
        boolean pendingComplete = phases.asList().stream().filter(e -> e.getAsJsonObject().get("phase").getAsString().equals("deepseek_request"))
                .allMatch(e -> e.getAsJsonObject().get("pending_ticks").getAsInt() == 250);
        result.addProperty("success", pendingComplete && trials.asList().stream().allMatch(e -> e.getAsJsonObject().get("success").getAsBoolean())
                && modelCalls.asList().stream().allMatch(e -> e.getAsJsonObject().get("outcome").getAsString().equals("completed")
                && e.getAsJsonObject().get("usage_known").getAsBoolean()));
        result.addProperty("wire_attempts", attempts.get());
        result.addProperty("calls", modelCalls.size());
        result.addProperty("timing_boundary", "ServerTickEvent.Pre to Post: processing; consecutive Pre timestamps: wall interval; fixture/launch bookkeeping after Post timestamp excluded");
        result.addProperty("pending_comparison_complete", pendingComplete);
        result.addProperty("pending_comparison_rule", "Only request_pending=true samples represent pending HTTP; short requests are recorded and do not count as a full 250-tick pending phase");
        result.add("interrupt_trials", trials);
        result.add("tick_phases", phases);
        result.add("model_calls", modelCalls);
        JsonArray differences = new JsonArray();
        for (int i = 0; i < phases.size(); i += 2) {
            JsonObject base = phases.get(i).getAsJsonObject(), model = phases.get(i + 1).getAsJsonObject();
            JsonObject difference = new JsonObject();
            difference.addProperty("pair", i / 2 + 1);
            difference.addProperty("pending_ticks", model.get("pending_ticks").getAsInt());
            if (model.get("pending_ticks").getAsInt() > 0)
                difference.addProperty("pending_minus_baseline_mean_processing_ms",
                        model.getAsJsonObject("pending_server_processing_ms").get("mean").getAsDouble()
                        - base.getAsJsonObject("server_processing_ms").get("mean").getAsDouble());
            differences.add(difference);
        }
        result.add("paired_processing_differences", differences);
        write("result.json", result);
        server.halt(false);
    }

    private void write(String name, Object value) throws IOException {
        Files.writeString(output.resolve(name), clean(JSON.toJson(value)));
    }

    private static JsonObject statistics(JsonArray samples, String key, boolean onlyPending) {
        double[] values = samples.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> !onlyPending || e.get("request_pending").getAsBoolean())
                .mapToDouble(e -> e.get(key).getAsDouble()).sorted().toArray();
        JsonObject stats = new JsonObject();
        stats.addProperty("n", values.length);
        if (values.length > 0) {
            stats.addProperty("mean", Arrays.stream(values).average().orElseThrow());
            stats.addProperty("p50", values[(int) Math.ceil(values.length * .5) - 1]);
            stats.addProperty("p95", values[(int) Math.ceil(values.length * .95) - 1]);
            stats.addProperty("max", values[values.length - 1]);
        }
        return stats;
    }

    private final class ObservedMove implements Task {
        private final Task delegate;
        private int ticks, ticksAtStop;
        private StopReason reason;
        private JsonArray heldAfterStop;
        private ObservedMove(Task delegate) { this.delegate = delegate; }
        public Preparation prepare(NumenPlayer body) { return delegate.prepare(body); }
        public boolean canRun(NumenPlayer body) { return delegate.canRun(body); }
        public void start(NumenPlayer body) { delegate.start(body); }
        public TaskState tick(NumenPlayer body) { ticks++; return delegate.tick(body); }
        public void stop(NumenPlayer body, StopReason why) {
            if (triggerNano != 0 && stopNano == 0) {
                stopNano = System.nanoTime();
                stopTick = body.level().getServer().getTickCount();
                reason = why;
                ticksAtStop = ticks;
            }
            delegate.stop(body, why);
            if (triggerNano != 0 && releaseNano == 0) {
                releaseNano = System.nanoTime();
                heldAfterStop = new JsonArray();
                for (var key : com.dwinovo.numen.pathing.body.Controls.Key.values())
                    if (body.controls().held(key)) heldAfterStop.add(key.name());
            }
        }
        public TaskResult result(TaskState terminal) { return delegate.result(terminal); }
        public String name() { return delegate.name(); }
    }
}
