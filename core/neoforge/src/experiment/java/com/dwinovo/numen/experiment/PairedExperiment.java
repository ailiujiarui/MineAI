package com.dwinovo.numen.experiment;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.*;
import com.dwinovo.numen.agent.llm.*;
import com.dwinovo.numen.agent.loop.*;
import com.dwinovo.numen.agent.memory.Compactor;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.request.*;
import com.dwinovo.numen.agent.tool.*;
import com.dwinovo.numen.entity.*;
import com.dwinovo.numen.network.payload.*;
import com.dwinovo.numen.program.*;
import com.dwinovo.numen.script.Modules;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** 有界真模型实验。夹具和终局观察在服务端,模型决定全部任务动作。 */
@Mod("numen_experiment")
public final class PairedExperiment {
    private static final Gson JSON = new Gson();
    private final Queue<Runnable> mail = new ConcurrentLinkedQueue<>();
    private JsonObject request;
    private NumenPlayer her;
    private ServerPlayer owner;
    private AgentLoop loop;
    private RuntimeState runtime;
    private BodySnapshot body;
    private ProgramUplink uplink;
    private long start;
    private int calls;
    private int accountedCalls;
    private int tools;
    private boolean finished;
    private boolean finalReply;
    private String opening;
    private long warmupStart;
    private String failure;
    private String failureMessage = "";
    private Usage usage = Usage.ZERO;
    private Path output;
    private String secret;

    public PairedExperiment() {
        if (System.getProperty("numen.experiment.request") != null) NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ServerTickEvent.Post event) {
        if (finished) return;
        try {
            if (request == null) { setup(event.getServer()); return; }
            Runnable next;
            while ((next = mail.poll()) != null) next.run();
            if (opening != null) {
                if (body != null) {
                    start = System.currentTimeMillis();
                    String text = opening;
                    opening = null;
                    say(text);
                } else if (System.currentTimeMillis() - warmupStart > 30_000) {
                    throw new IllegalStateException("no server body snapshot within 30 seconds");
                }
                return;
            }
            long elapsed = System.currentTimeMillis() - start;
            String kind = null;
            if (!her.isAlive()) kind = "death";
            else if (elapsed >= request.get("deadline_seconds").getAsLong() * 1000) kind = "deadline";
            else if (failure != null) kind = failure;
            if (kind != null) { finish(event.getServer(), kind); return; }
            if (finalReply && loop.status().phase() == null && !runtime.bodyTaskRunning()) {
                finalReply = false;
                JsonObject facts = observe();
                if (facts.get("success").getAsBoolean()) { finish(event.getServer(), "completed"); return; }
                if (calls >= request.get("max_calls").getAsInt()) { finish(event.getServer(), "step_limit"); return; }
                // 两组同样的续跑时机、预算和指令;唯一差异是这段只读服务端事实。
                say("Continue working toward the original goal. If you believe it is complete, give a final reply."
                        + (request.get("feedback").getAsBoolean() ? "\nServer verification: " + facts : ""));
            }
            loop.tick();
        } catch (Exception error) {
            finished = true;
            if (output != null) {
                JsonObject result = accounting();
                result.addProperty("kind", "harness_error");
                result.addProperty("message", clean(error.getClass().getSimpleName() + ": " + error.getMessage()));
                write("result.json", result);
            }
            event.getServer().halt(false);
        }
    }

    private void setup(MinecraftServer server) throws IOException {
        request = JsonParser.parseString(Files.readString(Path.of(System.getProperty("numen.experiment.request")))).getAsJsonObject();
        output = Path.of(request.get("output").getAsString());
        Files.createDirectories(output);
        JsonObject entry = JsonParser.parseString(Files.readString(Path.of(request.get("providers_file").getAsString())))
                .getAsJsonObject().getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> e.get("id").getAsString().equals(request.get("provider_id").getAsString()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("provider entry not found"));
        secret = field(entry, "api_key");
        if (secret.isBlank() || field(entry, "model").isBlank()) throw new IllegalArgumentException("provider requires api_key and model");
        LlmEndpoint endpoint = new LlmEndpoint(field(entry, "provider"), field(entry, "model"), secret,
                field(entry, "base_url"), field(entry, "proxy"), field(entry, "reasoning_effort"));
        write("model.json", Map.of("provider", endpoint.provider(), "model", endpoint.model(), "reasoning_effort", endpoint.reasoningEffort()));
        Set<String> allowed = Set.of("minecraft", "neoforge", "numen", "numen_api", "numen_experiment");
        for (var mod : net.neoforged.fml.ModList.get().getMods())
            if (!allowed.contains(mod.getModId())) throw new IllegalStateException("unexpected experiment mod: " + mod.getModId());
        var level = server.overworld();
        Modules.init(uuid -> output.resolve("lua"));
        com.dwinovo.numen.agent.memory.NoteBook.init(uuid -> output.resolve("memory"), () -> (int) (level.getDayTime() / 24000L));
        com.dwinovo.numen.api.NumenPlugins.bindSkills(root -> com.dwinovo.numen.agent.skill.SkillRegistry.instance().declareBundled(root));
        com.dwinovo.numen.agent.skill.SkillRegistry.instance().scan(null);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000, 0, false, false);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = 80; y <= 87; y++)
            level.setBlockAndUpdate(new BlockPos(x, y, z), (y == 80 ? Blocks.STONE :
                    (y == 87 || Math.abs(x) == 12 || Math.abs(z) == 12) ? Blocks.BARRIER : Blocks.AIR).defaultBlockState());
        owner = ExperimentOwner.join(server, this::receive);
        her = CompanionFactory.spawn(server, UUID.randomUUID(), "EvalCompanion", owner.getUUID(), level, new Vec3(0.5, 81, 0.5));
        String goal = switch (request.get("scenario").getAsString()) {
            case "collect" -> {
                her.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
                for (int x = 3; x < 11; x++) level.setBlockAndUpdate(new BlockPos(x, 81, 3), Blocks.STONE.defaultBlockState());
                yield "Mine the eight stone blocks at x=3..10,y=81,z=3 and collect at least 8 minecraft:cobblestone into your own backpack.";
            }
            case "structure" -> {
                her.getInventory().add(new ItemStack(Items.OAK_PLANKS, 32));
                yield "Build exactly a solid 3-wide by 2-high oak-plank wall at x=3..5,y=81..82,z=3. All six cells must be minecraft:oak_planks; all other cells in x=2..6,y=81..83,z=2..4 must remain air.";
            }
            case "furnace" -> {
                her.getInventory().add(new ItemStack(Items.FURNACE));
                her.getInventory().add(new ItemStack(Items.COAL, 4));
                her.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
                yield "Place a vanilla furnace at 3,81,3, fuel it with coal, smelt the three raw iron, and collect at least 3 minecraft:iron_ingot into your own backpack. Leave the furnace at the specified cell with coal remaining in its fuel slot.";
            }
            default -> throw new IllegalArgumentException("unknown scenario");
        };
        runtime = new RuntimeState(her.getUUID(), () -> body);
        ConvoLog log = ConvoLog.atFile(output.resolve("conversation.jsonl"));
        ConvoState convo = new ConvoState(msg -> log.append(msg, null));
        MemoryPreamble memory = new MemoryPreamble(com.dwinovo.numen.agent.memory.NoteBook.of(her.getUUID()));
        ModuleSync sync = new ModuleSync();
        LoopbackTransport transport = new LoopbackTransport(sync, Modules::of);
        uplink = new ProgramUplink(sync, payload -> {
            if (payload instanceof RunProgramPayload p) {
                ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request(p.programId(), p.code(), p.modules(), true),
                        transport, CallObserver.NONE, result -> mail.add(() -> uplink.deliver(p.programId(), result)));
            } else if (payload instanceof StopProgramPayload p) StopProgramPayload.handle(p, owner);
            else if (payload instanceof CancelTasksPayload p) CancelTasksPayload.handle(p, owner);
            else throw new IllegalArgumentException("unsupported uplink");
        }, Modules::of);
        CompanionToolPort port = new CompanionToolPort(her.getUUID(), () -> (ToolAnchor) her::getUUID, uplink,
                (id, receipt) -> mail.add(() -> loop.push(List.of(com.dwinovo.numen.event.NumenEvents.programStopped(
                        level.getDayTime(), id, RunResult.messageOf(receipt), System.currentTimeMillis())))));
        Compactor compactor = new Compactor(her.getUUID().toString(), convo, log, () -> entry.has("ctx") && entry.get("ctx").getAsInt() > 0
                ? entry.get("ctx").getAsInt() : com.dwinovo.numen.agent.provider.ProviderRegistry.contextWindow(endpoint.provider(), endpoint.model()));
        ModelPort model = new ModelPort() {
            public String unavailable() { return null; }
            public ModelRequest turnRequest() { return AgentRequestContext.turn(convo.snapshot(), runtime.xml(), null, Modules.of(her.getUUID())); }
            public void call(ModelRequest req, CancelToken cancel, Consumer<Delta> delta, Consumer<ModelOutcome> done) {
                if (calls >= request.get("max_calls").getAsInt()) { failure = "step_limit"; return; }
                if (System.currentTimeMillis() - start >= request.get("deadline_seconds").getAsLong() * 1000) { failure = "deadline"; return; }
                calls++;
                write("accounting.json", accounting());
                trace("model_call", Map.of("call", calls, "purpose", loop.status().phase() == Phase.COMPACT ? "COMPACT" : "TURN"));
                java.util.concurrent.atomic.AtomicReference<JsonObject> usageFrame = new java.util.concurrent.atomic.AtomicReference<>();
                NumenLlmClient.forEndpoint(endpoint).chatStreaming(req.messages(), req.tools(), req.systemPrompt(), cancel, chunk -> {
                    if (chunk.has("usage") && chunk.get("usage").isJsonObject()) usageFrame.set(chunk.getAsJsonObject("usage"));
                })
                        .whenComplete((answer, error) -> mail.add(() -> {
                            if (cancel.isCancelled() || finished) return;
                            if (error != null) {
                                failure = "model_error";
                                Throwable cause = error;
                                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                                failureMessage = clean(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                                trace("model_error", Map.of("message", failureMessage));
                            } else {
                                JsonObject frame = usageFrame.get();
                                boolean known = frame != null && frame.has("prompt_tokens") && frame.has("completion_tokens")
                                        && frame.get("prompt_tokens").getAsLong() >= 0 && frame.get("completion_tokens").getAsLong() >= 0
                                        && frame.get("prompt_tokens").getAsLong() == answer.usage().promptTokens()
                                        && frame.get("completion_tokens").getAsLong() == answer.usage().output();
                                if (known) accountedCalls++;
                                trace("usage_presence", Map.of("known", known));
                                done.accept(new ModelOutcome.Answered(answer.turn(), answer.usage()));
                                write("accounting.json", accounting());
                            }
                        }));
            }
        };
        HostPort host = new HostPort() {
            public long now() { return System.currentTimeMillis(); }
            public int initiativeLevel() { return EventQueue.DEFAULT_LEVEL; }
            public boolean externallyDriven() { return false; }
            public String injectionPreamble() { return memory.next(); }
            public boolean bodyTaskRunning() { return runtime.bodyTaskRunning(); }
            public String activity() { return port.currentToolName(); }
        };
        ToolPort bounded = new ToolPort() {
            public void run(List<com.dwinovo.numen.agent.provider.LlmToolCall> batch, Sink sink) {
                if (tools + batch.size() > request.get("max_tools").getAsInt()) { failure = "tool_limit"; return; }
                if (System.currentTimeMillis() - start >= request.get("deadline_seconds").getAsLong() * 1000) { failure = "deadline"; return; }
                port.run(batch, sink);
            }
            public void arrived(EventQueue.Entry entry, boolean urgent) { port.arrived(entry, urgent); }
            public List<String> cancel(boolean stopBody) { return port.cancel(stopBody); }
        };
        loop = new AgentLoop("experiment", model, bounded, convo, new EventQueue(EventQueue.Journal.NONE), compactor, host);
        loop.subscribe(compactor::on);
        loop.subscribe(runtime::on);
        loop.subscribe(memory::on);
        loop.subscribe(e -> {
            trace(e.getClass().getSimpleName(), e);
            if (e instanceof LoopEvent.ModelUsed u) usage = usage.plus(u.usage());
            if (e instanceof LoopEvent.ToolStarted) { tools++; write("accounting.json", accounting()); }
            if (e instanceof LoopEvent.RunEnded r && r.end() == RunEnd.DONE) finalReply = true;
            if (e instanceof LoopEvent.TurnFailed f) { failure = "model_error"; failureMessage = f.words(); }
        });
        warmupStart = System.currentTimeMillis();
        opening = "Fixture initialization: you spawned at 0,81,0 in a peaceful enclosed stone-floor arena, survival mode. Your starting inventory was supplied by the fixture. Goal: " + goal;
    }

    private void receive(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        mail.add(() -> {
            if (loop == null || finished) return;
            if (payload instanceof NumenStatePayload p && p.uuid().equals(her.getUUID())) body = BodySnapshot.of(p, System.currentTimeMillis());
            if (payload instanceof CurrentTaskPayload p && p.entityUuid().equals(her.getUUID())) runtime.onCurrentTask(p);
            if (payload instanceof NumenEventPayload p && p.entityUuid().equals(her.getUUID())) loop.push(p.entries());
        });
    }

    private JsonObject observe() {
        JsonObject facts = new JsonObject();
        boolean success;
        String scenario = request.get("scenario").getAsString();
        if (scenario.equals("structure")) {
            JsonArray mismatches = new JsonArray();
            for (int x = 2; x <= 6; x++) for (int y = 81; y <= 83; y++) for (int z = 2; z <= 4; z++) {
                var expected = x >= 3 && x <= 5 && y <= 82 && z == 3 ? Blocks.OAK_PLANKS : Blocks.AIR;
                var actual = her.serverLevel().getBlockState(new BlockPos(x, y, z));
                if (!actual.is(expected)) mismatches.add(x + "," + y + "," + z + " expected=" + expected + " actual=" + actual);
            }
            facts.add("mismatches", mismatches);
            success = mismatches.isEmpty();
        } else {
            Item item = scenario.equals("collect") ? Items.COBBLESTONE : Items.IRON_INGOT;
            int count = 0;
            for (int i = 0; i < 36; i++) {
                var stack = her.getInventory().getItem(i);
                if (stack.is(item)) count += stack.getCount();
            }
            int expected = scenario.equals("collect") ? 8 : 3;
            facts.addProperty("expected", expected);
            facts.addProperty("actual", count);
            success = count >= expected;
            if (scenario.equals("collect")) {
                int remaining = 0;
                for (int x = 3; x < 11; x++) if (!her.serverLevel().getBlockState(new BlockPos(x, 81, 3)).isAir()) remaining++;
                facts.addProperty("target_blocks_remaining", remaining);
                success &= remaining == 0;
            }
            if (scenario.equals("furnace")) {
                var entity = her.serverLevel().getBlockEntity(new BlockPos(3, 81, 3));
                boolean fueled = entity instanceof FurnaceBlockEntity furnace && furnace.getItem(1).is(Items.COAL);
                facts.addProperty("furnace_with_coal_at_target", fueled);
                success &= fueled;
            }
        }
        facts.addProperty("success", success);
        return facts;
    }

    private void finish(MinecraftServer server, String kind) {
        JsonObject facts = observe();
        long elapsed = System.currentTimeMillis() - start;
        boolean timely = elapsed <= request.get("deadline_seconds").getAsLong() * 1000;
        facts.addProperty("world_goal_holds", facts.get("success").getAsBoolean());
        facts.addProperty("success", timely && her.isAlive() && facts.get("success").getAsBoolean());
        facts.addProperty("kind", facts.get("success").getAsBoolean() ? "completed" : kind);
        facts.addProperty("message", facts.get("success").getAsBoolean() ? "server facts satisfy fixture goal" :
                "server facts do not satisfy fixture goal; termination=" + kind + "; " + clean(failureMessage));
        facts.addProperty("elapsed_ms", elapsed);
        facts.addProperty("deadline_seconds", request.get("deadline_seconds").getAsLong());
        facts.addProperty("calls", calls);
        facts.addProperty("tools", tools);
        facts.add("usage", JSON.toJsonTree(usage));
        facts.addProperty("usage_complete", calls == accountedCalls);
        facts.addProperty("fresh_tokens", usage.fresh());
        finished = true;
        loop.halt(HaltReason.OWNER_STOP);
        write("result.json", facts);
        CompanionFactory.despawn(server, her);
        server.getPlayerList().remove(owner);
        server.halt(false);
    }

    private void say(String text) { loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(text), System.currentTimeMillis(), false))); }
    private JsonObject accounting() {
        JsonObject record = new JsonObject();
        record.addProperty("calls", calls);
        record.addProperty("tools", tools);
        record.addProperty("usage_complete", calls == accountedCalls);
        record.add("usage", JSON.toJsonTree(usage));
        return record;
    }
    private static String field(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
    private String clean(String text) { return secret == null || secret.isEmpty() ? text : text.replace(secret, "[redacted]"); }
    private void write(String file, Object value) {
        try { Files.writeString(output.resolve(file), clean(JSON.toJson(value))); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void trace(String type, Object value) {
        try { Files.writeString(output.resolve("trace.jsonl"), clean(JSON.toJson(Map.of("elapsed_ms", start == 0 ? 0 : System.currentTimeMillis() - start,
                "type", type, "data", value))) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
