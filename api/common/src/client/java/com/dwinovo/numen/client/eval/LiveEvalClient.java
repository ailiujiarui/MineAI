package com.dwinovo.numen.client.eval;

import com.dwinovo.numen.agent.acceptance.LiveEvalRun;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Consumer;

/** Opt-in live evaluation of the production client brain against an isolated integrated server. */
public final class LiveEvalClient {
    private static final String REQUEST = System.getProperty("numen.eval.live");
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static LiveEvalClient active;
    private static boolean initialized;

    private final Minecraft mc = Minecraft.getInstance();
    private final JsonObject request;
    private final JsonObject scenario;
    private final Path output;
    private final LiveEvalRun run;
    private final Consumer<LoopEvent> observer = this::onEvent;
    private final long createdAt = System.nanoTime();
    private UUID companion;
    private EntityAgentLoop brain;
    private boolean worldRequested;
    private boolean setupRequested;
    private boolean observing;
    private boolean started;
    private boolean stopping;
    private boolean exitRequested;
    private boolean firstText;
    private int readyTicks;
    private int traceWritten;
    private long nextObservation;
    private long nextFlush;
    private long startedAt;
    private long observationStartedAt;
    private long stoppingAt;

    private LiveEvalClient(JsonObject request) throws IOException {
        this.request = request;
        scenario = request.getAsJsonObject("scenario");
        output = Path.of(request.get("output_dir").getAsString()).toAbsolutePath().normalize();
        Files.createDirectories(output);
        run = new LiveEvalRun(scenario);
    }

    /** Runs even at the title screen; without the explicit JVM property it does nothing. */
    public static void tick() {
        if (REQUEST == null || REQUEST.isBlank()) return;
        if (!initialized) {
            initialized = true;
            try {
                active = new LiveEvalClient(JsonParser.parseString(Files.readString(Path.of(REQUEST)))
                        .getAsJsonObject());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot initialize live evaluation request", e);
            }
        }
        if (active.stopping) {
            if (!active.exitRequested && System.nanoTime() - active.stoppingAt >= 5_000_000_000L) {
                active.saveAndExit();
            }
            return;
        }
        try {
            active.advance();
        } catch (Exception e) {
            // The launcher keeps the full game log; the portable report excludes provider error bodies.
            active.fail("Client harness error: " + failureTypes(e));
        }
    }

    private void advance() throws IOException {
        long now = System.nanoTime();
        if (run.finished()) {
            stop();
            return;
        }
        if (!started && now - createdAt > 180_000_000_000L) {
            fail("World or companion did not become ready within 180 seconds");
            return;
        }
        if (started && (now - startedAt) / 1_000_000_000.0
                >= scenario.getAsJsonObject("budget").get("max_seconds").getAsDouble()) {
            run.finish("budget_exhausted", "max_seconds", now);
            stop();
            return;
        }
        if (observing && now - observationStartedAt >= 30_000_000_000L) {
            fail("Authoritative world observation did not complete within 30 seconds");
            return;
        }
        if (!worldRequested) {
            if (mc.getOverlay() != null || mc.screen == null) return;
            var entry = ProviderLibrary.instance().get(request.get("provider_id").getAsString());
            if (entry == null || !ProviderRegistry.has(entry.provider()) || entry.model() == null
                    || entry.model().isBlank() || entry.apiKey() == null || entry.apiKey().isBlank()) {
                fail("Selected provider entry is missing or incomplete");
                return;
            }
            JsonObject model = new JsonObject();
            model.addProperty("provider", entry.provider());
            model.addProperty("model", entry.model());
            model.addProperty("context_window", entry.contextWindow());
            model.addProperty("reasoning_effort", entry.reasoningEffort());
            write("model.json", model);
            mc.options.pauseOnLostFocus = false;
            worldRequested = true;
            LiveEvalWorld.create(mc, request.get("world_name").getAsString(),
                    scenario.get("seed").getAsLong(), scenario.get("world_preset").getAsString());
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) {
            if (started) fail("Integrated server disconnected during evaluation");
            return;
        }
        if (!setupRequested) {
            setupRequested = true;
            mc.setScreen(null);
            UUID owner = mc.player.getUUID();
            server.submit(() -> LiveEvalWorld.setup(server, owner, scenario)).whenComplete((uuid, error) ->
                    mc.execute(() -> {
                        if (stopping) return;
                        if (error != null) fail("World fixture setup failed: " + failureTypes(error));
                        else companion = uuid;
                    }));
            return;
        }
        if (companion == null) return;
        if (!started) {
            if (ClientNumenLookup.resolve(companion) == null) {
                readyTicks = 0;
                return;
            }
            if (++readyTicks < 25) return;
        }
        if (!observing && now >= nextObservation) {
            observing = true;
            observationStartedAt = now;
            nextObservation = now + 200_000_000L;
            server.submit(() -> new Observation(System.nanoTime(), LiveEvalWorld.snapshot(server, companion)))
                    .whenComplete((observation, error) -> mc.execute(() -> {
                        observing = false;
                        if (stopping) return;
                        if (error != null) {
                            fail("Authoritative world observation failed: " + failureTypes(error));
                            return;
                        }
                        try {
                            if (!started) begin(observation);
                            else run.observe(observation.at(), observation.snapshot());
                        } catch (Exception e) {
                            fail("World observation callback failed: " + failureTypes(e));
                        }
                    }));
        }
        if (started && now >= nextFlush) {
            flushEvidence();
            nextFlush = now + 1_000_000_000L;
        }
    }

    private void begin(Observation initial) {
        started = true;
        startedAt = initial.at();
        run.start(initial.at(), initial.snapshot());
        JsonObject fixture = new JsonObject();
        fixture.add("setup", scenario.get("setup").deepCopy());
        fixture.addProperty("goal_max_turns", GoalState.MAX_GOAL_TURNS);
        fixture.addProperty("goal_stuck_limit", GoalState.STUCK_STREAK_TO_GIVE_UP);
        fixture.addProperty("owner_mode", "spectator");
        fixture.addProperty("owner_follows_companion", false);
        JsonArray tools = new JsonArray();
        ToolRegistry.all().forEach(tool -> tools.add(tool.name()));
        fixture.add("registered_tools", tools);
        int gameTests = GameTestRegistry.getAllTestFunctions().size();
        fixture.addProperty("registered_game_tests", gameTests);
        fixture.addProperty("client_tracking_limit", "Owner remains at spawn; built-in body and perception tools resolve the companion on the server by UUID. Client presentation and plugins that require a client entity may lose tracking beyond viewing distance or after dimension travel");
        run.event("fixture", fixture, initial.at());
        if (run.finished()) return;
        if (gameTests != 0) {
            run.finish("infra_error", "GameTest fixtures active in live evaluation", initial.at());
            return;
        }
        brain = AgentLoopRegistry.getOrCreate(companion);
        brain.observe(observer);
        brain.executionBudget(() -> run.tryModelCall(System.nanoTime()),
                () -> run.beforeTool(System.nanoTime()));
        String prompt = scenario.get("prompt").getAsString();
        JsonObject input = new JsonObject();
        input.addProperty("text", prompt);
        run.event("owner_goal", input, System.nanoTime());
        // Queue the ordinary /goal input while unbound, so binding cannot send a greeting first.
        brain.setGoal(GoalState.of(prompt, System.currentTimeMillis()), "/goal " + prompt);
        brain.setProviderEntry(request.get("provider_id").getAsString());
    }

    private void onEvent(LoopEvent event) {
        long now = System.nanoTime();
        JsonObject data = new JsonObject();
        switch (event) {
            case LoopEvent.ModelUsed used -> {
                run.used(used.usage());
                data.addProperty("purpose", used.purpose().name());
                run.event("model_usage", data, now);
            }
            case LoopEvent.ModelDelta delta -> {
                if (!firstText && delta.content() != null && !delta.content().isEmpty()) {
                    firstText = true;
                    run.event("first_text_delta", data, now);
                }
            }
            case LoopEvent.AssistantMessage message -> {
                data.addProperty("content", message.turn().content());
                JsonArray calls = new JsonArray();
                message.turn().toolCalls().forEach(call -> calls.add(call.toOpenAIJson()));
                data.add("tool_calls", calls);
                run.event("assistant", data, now);
            }
            case LoopEvent.ToolStarted tool -> {
                data.add("call", tool.call().toOpenAIJson());
                run.event("tool_started", data, now);
            }
            case LoopEvent.ToolFinished tool -> {
                data.add("call", tool.call().toOpenAIJson());
                data.addProperty("result", tool.resultJson());
                run.event("tool_finished", data, now);
            }
            case LoopEvent.TurnFailed ignored -> {
                // A refusal at the execution budget is finalized with the next authoritative observation.
                if (run.beforeTool(now)) run.finish("infra_error", "Model request failed after runtime retries", now);
            }
            case LoopEvent.Halted halted -> {
                data.addProperty("reason", halted.reason().name());
                run.event("halted", data, now);
            }
            default -> { }
        }
    }

    private void fail(String reason) {
        if (stopping) return;
        run.finish("infra_error", reason, System.nanoTime());
        stop();
    }

    private void stop() {
        if (stopping) return;
        stopping = true;
        stoppingAt = System.nanoTime();
        try {
            if (brain != null) {
                brain.abort();
                brain.unobserve(observer);
                // Keep the closed run's budget attached until shutdown: delayed events cannot issue requests.
            }
            // Save before waiting on the server: a stalled world must not erase the final report.
            saveEvidence();
        } catch (Exception e) {
            exitRequested = true;
            mc.stop();
            throw new IllegalStateException("Cannot finalize live evaluation: " + failureTypes(e));
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (companion != null && server != null) {
            server.submit(() -> LiveEvalWorld.stop(server, companion)).whenComplete((ignored, error) ->
                    mc.execute(this::saveAndExit));
        } else saveAndExit();
    }

    private void saveAndExit() {
        if (exitRequested) return;
        exitRequested = true;
        try {
            saveEvidence();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist live evaluation evidence", e);
        } finally {
            mc.stop();
        }
    }

    private void saveEvidence() throws IOException {
        flushEvidence();
        write("trace.json", run.trace());
        write("result.json", run.report());
    }

    /** Exception messages may contain remote response bodies; only class names enter portable evidence. */
    private static String failureTypes(Throwable error) {
        StringBuilder types = new StringBuilder();
        for (int i = 0; error != null && i < 5; i++) {
            if (i > 0) types.append(" <- ");
            types.append(error.getClass().getSimpleName());
            Throwable cause = error.getCause();
            if (cause == error) break;
            error = cause;
        }
        return types.toString();
    }

    private void flushEvidence() throws IOException {
        JsonArray trace = run.traceFrom(traceWritten);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < trace.size(); i++) lines.append(trace.get(i)).append('\n');
        if (!lines.isEmpty()) Files.writeString(output.resolve("trace.jsonl"), lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        traceWritten += trace.size();
        write("progress.json", run.report());
    }

    private void write(String filename, com.google.gson.JsonElement value) throws IOException {
        Path target = output.resolve(filename);
        Path temporary = output.resolve(filename + ".tmp");
        Files.writeString(temporary, JSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private record Observation(long at, JsonObject snapshot) {}
}
