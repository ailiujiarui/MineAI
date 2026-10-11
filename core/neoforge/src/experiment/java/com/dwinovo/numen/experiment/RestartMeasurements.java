package com.dwinovo.numen.experiment;

import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.program.CallObserver;
import com.dwinovo.numen.program.ServerPrograms;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 两个独立进程间的身体/任务恢复测量;只在 prepare 派活,resume 只观察产品自动恢复。 */
public final class RestartMeasurements {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static Trial active;

    private RestartMeasurements() {}

    /** MeasurementExperiment 在正常服务端 tick 调用;同一 trial 的两个 phase 使用相同 output。 */
    public static void tick(MinecraftServer server, JsonObject request, Path output) {
        if (active == null) active = new Trial(server, request, output);
        if (active.finished) return;
        try {
            active.tick();
        } catch (Exception error) {
            active.finish("harness_error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static final class Trial {
        private final MinecraftServer server;
        private final JsonObject request;
        private final Path output;
        private final long start = System.currentTimeMillis();
        private final long pid = ProcessHandle.current().pid();
        private final String processStart = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        private final JsonArray events = new JsonArray();
        private JsonObject checkpoint;
        private String phase;
        private int rep;
        private UUID companionUuid;
        private UUID ownerUuid;
        private Vec3 target;
        private Vec3 initial;
        private Vec3 before;
        private NumenPlayer body;
        private TaskRecord task;
        private long acceptedTick = -1;
        private boolean initialized;
        private boolean finished;
        private boolean bodyPositionRestored;
        private boolean taskRestored;
        private ScriptCall.Finish completion;

        Trial(MinecraftServer server, JsonObject request, Path output) {
            this.server = server;
            this.request = request;
            this.output = output;
        }

        void tick() throws IOException {
            if (!initialized) {
                initialize();
                initialized = true;
                return;
            }
            long deadline = request.has("deadline_seconds") ? request.get("deadline_seconds").getAsLong() : 90;
            if (System.currentTimeMillis() - start >= deadline * 1000L) {
                finish("deadline", "phase exceeded " + deadline + " seconds");
                return;
            }
            if ("prepare".equals(phase)) prepareTick();
            else resumeTick();
        }

        private void initialize() throws IOException {
            phase = request.get("phase").getAsString();
            rep = request.get("rep").getAsInt();
            if (!"restart".equals(request.get("mode").getAsString())
                    || !(phase.equals("prepare") || phase.equals("resume")) || rep < 0) {
                throw new IllegalArgumentException("requires mode=restart, phase=prepare|resume and rep>=0");
            }
            Files.createDirectories(output);
            if (phase.equals("resume")) {
                checkpoint = JsonParser.parseString(Files.readString(output.resolve("checkpoint.json"))).getAsJsonObject();
                if (checkpoint.get("rep").getAsInt() != rep) throw new IllegalStateException("checkpoint rep mismatch");
                if (checkpoint.get("pid").getAsLong() == pid
                        && checkpoint.get("process_start").getAsString().equals(processStart)) {
                    throw new IllegalStateException("resume must run in a new JVM process");
                }
                if (!checkpoint.get("success").getAsBoolean()) {
                    finish("prepare_failed", checkpoint.get("message").getAsString());
                    return;
                }
                companionUuid = UUID.fromString(checkpoint.get("companion_uuid").getAsString());
                ownerUuid = UUID.fromString(checkpoint.get("owner_uuid").getAsString());
                initial = position(checkpoint.getAsJsonObject("initial_pos"));
                before = position(checkpoint.getAsJsonObject("beforeRestart"));
                target = position(checkpoint.getAsJsonObject("target"));
                CompanionRegistry registry = CompanionRegistry.get(server);
                if (!registry.worldId().equals(checkpoint.get("world_id").getAsString())) {
                    throw new IllegalStateException("resume loaded a different world");
                }
                CompanionRegistry.Entry entry = registry.find(companionUuid);
                if (entry == null || !entry.owner().equals(ownerUuid)
                        || !entry.taskLua().equals(checkpoint.get("task_lua").getAsString())
                        || !entry.taskName().equals(checkpoint.get("task_name").getAsString())) {
                    finish("persistence_missing", "saved companion/task registry did not survive shutdown");
                    return;
                }
                JsonObject saved = new JsonObject();
                saved.addProperty("kind", "registry_loaded_from_world");
                saved.addProperty("task_name", entry.taskName());
                saved.addProperty("task_lua", entry.taskLua());
                saved.addProperty("time_ms", System.currentTimeMillis());
                events.add(saved);
                if (NumenPlayer.findByUuid(server, companionUuid) != null) {
                    throw new IllegalStateException("companion already live before fixture owner login");
                }
                // 真实 PlayerLoggedInEvent → scheduleRestoreFor → 正常 scheduler tick;没有手工 respawn/replay。
                RestartOwner.join(server, ownerUuid, this::receive);
            } else {
                if (Files.exists(output.resolve("checkpoint.json")) || Files.exists(output.resolve("result.json"))) {
                    throw new IllegalStateException("prepare requires a fresh trial output");
                }
                ownerUuid = UUID.randomUUID();
                var level = server.overworld();
                server.setDifficulty(Difficulty.PEACEFUL, true);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.setDayTime(6000);
                // 独立测试存档的平坦走廊;目标固定 40 格,中断固定在受理后 30 个游戏刻。
                for (int x = -4; x <= 48; x++) for (int z = -4; z <= 4; z++) for (int y = 80; y <= 85; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z),
                            (y == 80 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                }
                ServerPlayer owner = RestartOwner.join(server, ownerUuid, this::receive);
                owner.teleportTo(-2.5, 81, 2.5);
                body = Companions.summon(server, ownerUuid, "RestartBody", level, new Vec3(0.5, 81, 0.5));
                companionUuid = body.getUUID();
                initial = body.position();
                target = new Vec3(40.5, 81, 0.5);
                ServerPrograms.launch(body, "restart-prepare-" + rep,
                        "numen.move.to({x=40,y=81,z=0})", CallObserver.NONE, receipt -> {
                            JsonObject event = new JsonObject();
                            event.addProperty("kind", "prepare_program_receipt");
                            event.addProperty("receipt", receipt);
                            events.add(event);
                        });
            }
        }

        private void prepareTick() {
            TaskRecord current = CompanionTickDispatcher.currentTaskFor(companionUuid);
            if (task == null && current != null) {
                task = current;
                acceptedTick = server.overworld().getGameTime();
            }
            if (task == null) {
                if (completion != null) finish("prepare_task_failed", completion.words());
                return;
            }
            if (current != task || task.getState() != TaskState.RUNNING) {
                finish("prepare_task_ended", "task ended before the fixed interruption tick");
                return;
            }
            if (server.overworld().getGameTime() - acceptedTick < 30) return;
            before = body.position();
            if (before.distanceTo(initial) < 1 || before.distanceTo(target) < 10) {
                finish("invalid_interruption", "fixed 30-tick checkpoint did not capture an in-progress walk");
                return;
            }
            CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
            if (entry == null || entry.taskLua().isBlank()) {
                finish("not_persistable", "production dispatch did not save a replay recipe");
                return;
            }
            // 保存真实 SavedData、world 和 playerdata,不移除身体(移除会结算掉正在做的活)。
            String worldId = CompanionRegistry.get(server).worldId();
            server.getPlayerList().saveAll();
            server.saveEverything(false, true, true);
            checkpoint = accounting("prepared", "world and playerdata saved while task RUNNING");
            checkpoint.addProperty("success", true);
            checkpoint.addProperty("world_id", worldId);
            checkpoint.addProperty("task_name", entry.taskName());
            checkpoint.addProperty("task_lua", entry.taskLua());
            checkpoint.add("task_before", describe(task));
            checkpoint.addProperty("interruption_ticks", server.overworld().getGameTime() - acceptedTick);
            write("checkpoint.json", checkpoint);
            write("prepared.json", checkpoint);
            finished = true;
            server.halt(false);
        }

        private void resumeTick() {
            if (body == null) {
                body = NumenPlayer.findByUuid(server, companionUuid);
                if (body == null) return;
                Vec3 loaded = body.position();
                bodyPositionRestored = body.getOwnerUuid().equals(ownerUuid)
                        && body.serverLevel() == server.overworld() && loaded.distanceTo(before) < 1;
                JsonObject evidence = new JsonObject();
                evidence.addProperty("kind", "body_auto_loaded");
                evidence.add("position", position(loaded));
                evidence.addProperty("distance_from_saved", loaded.distanceTo(before));
                evidence.addProperty("time_ms", System.currentTimeMillis());
                events.add(evidence);
                if (!bodyPositionRestored) {
                    finish("body_restore_mismatch", "auto-loaded body does not match saved position/owner/dimension");
                    return;
                }
            }
            if (!body.isAlive()) { finish("death", "restored body died"); return; }
            TaskRecord current = CompanionTickDispatcher.currentTaskFor(companionUuid);
            if (current != null && task == null) {
                task = current;
                // TaskPersistence.launch 的 tag 为 restored-<保存的任务名>,Program 的调用序号接在 # 后。
                String prefix = "restored-" + checkpoint.get("task_name").getAsString() + "-";
                taskRestored = task.getState() == TaskState.RUNNING && task.isAsync()
                        && task.getToolName().equals(checkpoint.get("task_name").getAsString())
                        && task.getToolCallId().startsWith(prefix) && task.getToolCallId().contains("#");
                events.add(describe(task));
                if (!taskRestored) { finish("task_origin_mismatch", "live task lacks TaskPersistence replay origin"); return; }
            }
            if (completion != null) {
                boolean success = bodyPositionRestored && taskRestored && task != null
                        && completion.task().equals(task.publicId()) && "done".equals(completion.status())
                        && task.getState() == TaskState.SUCCESS && body.position().distanceTo(target) < 1
                        && body.position().distanceTo(before) > 10;
                finish(success ? "completed" : "replay_failed", completion.words());
            } else if (task != null && task.getState().isTerminal()) {
                finish("completion_evidence_missing", "replayed task ended without its task_finished evidence");
            }
        }

        private void receive(CustomPacketPayload payload) {
            if (payload instanceof NumenEventPayload message && message.entityUuid().equals(companionUuid)) {
                for (var entry : message.entries()) {
                    ScriptCall.Finish finish = NumenEvents.finishOf(entry);
                    if (finish == null) continue;
                    completion = finish;
                    JsonObject event = new JsonObject();
                    event.addProperty("kind", "task_finished");
                    event.addProperty("task_id", finish.task());
                    event.addProperty("status", finish.status());
                    event.addProperty("words", finish.words());
                    event.addProperty("time_ms", System.currentTimeMillis());
                    event.add("result", finish.result());
                    events.add(event);
                }
            }
        }

        void finish(String kind, String message) {
            if (finished) return;
            finished = true;
            try {
                JsonObject result = accounting(kind, message);
                if ("prepare".equals(phase)) {
                    write("checkpoint.json", result);
                    write("prepared.json", result);
                } else {
                    if (checkpoint != null) result.add("checkpoint", checkpoint);
                    write("result.json", result);
                }
            } finally {
                server.halt(false);
            }
        }

        private JsonObject accounting(String kind, String message) {
            JsonObject result = new JsonObject();
            result.addProperty("mode", "restart");
            result.addProperty("phase", phase);
            result.addProperty("rep", rep);
            result.addProperty("kind", kind);
            result.addProperty("success", "completed".equals(kind));
            result.addProperty("message", message);
            result.addProperty("pid", pid);
            result.addProperty("process_start", processStart);
            result.addProperty("start_ms", start);
            result.addProperty("end_ms", System.currentTimeMillis());
            result.addProperty("elapsed_ms", System.currentTimeMillis() - start);
            result.addProperty("game_tick", server.overworld().getGameTime());
            result.addProperty("owner_uuid", ownerUuid == null ? "" : ownerUuid.toString());
            result.addProperty("companion_uuid", companionUuid == null ? "" : companionUuid.toString());
            result.add("initial_pos", position(initial));
            result.add("beforeRestart", position(before));
            result.add("target", position(target));
            result.add("afterPos", position(body == null ? null : body.position()));
            if (body != null && target != null) result.addProperty("distance_to_target", body.position().distanceTo(target));
            result.addProperty("body_position_restored", bodyPositionRestored);
            result.addProperty("task_restored", taskRestored);
            result.addProperty("llm_session_resumed", false);
            result.addProperty("measurement_scope", "server body and TaskPersistence replay; no client LLM session");
            result.add("task", task == null ? null : describe(task));
            result.add("evidence", events);
            return result;
        }

        private void write(String name, JsonObject value) {
            try {
                Files.createDirectories(output);
                Files.writeString(output.resolve(name), JSON.toJson(value));
            } catch (IOException error) {
                throw new UncheckedIOException("could not save " + output.resolve(name), error);
            }
        }
    }

    private static JsonObject describe(TaskRecord task) {
        JsonObject result = new JsonObject();
        result.addProperty("task_id", task.publicId());
        result.addProperty("status", task.getState().name());
        result.addProperty("origin", task.getToolCallId());
        result.addProperty("name", task.getToolName());
        result.addProperty("describe", task.describe());
        result.addProperty("started_game_tick", task.getStartedGameTime());
        return result;
    }

    private static JsonObject position(Vec3 pos) {
        if (pos == null) return null;
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.x);
        result.addProperty("y", pos.y);
        result.addProperty("z", pos.z);
        return result;
    }

    private static Vec3 position(JsonObject pos) {
        return new Vec3(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
    }
}
