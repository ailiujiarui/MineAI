package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.TimerRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 身体层评测:固定工具调用驱动真实世界,只测工具入口之后的响应,不含模型、网络或自然语言理解。
 * 游戏刻与单调时钟各自记账;GameTestServer 不按实时节拍运行,墙钟只作进程诊断,不代表玩家等待时间。
 * 只有设置 numen.eval.output 时才向该目录追加 body.jsonl。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class BenchmarkGameTests {

    @BeforeBatch(batch = "numen_benchmark")
    public static void prepareBenchmarkBatch(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    /** 空闲身体接到 follow:先真正移步,再跟到主人身边,任务仍驻留。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_benchmark")
    public static void follow_from_idle(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-follow-idle");
        NumenPlayer body = result.body(spawnAt(helper, "eval_follower", new BlockPos(2, 2, 2), false));
        NumenPlayer owner = result.body(presentOwner(helper, body, "eval_owner"));
        BlockPos ownerAt = helper.absolutePos(new BlockPos(13, 2, 13));
        Vec3 ownerTarget = new Vec3(ownerAt.getX() + 0.5, ownerAt.getY(), ownerAt.getZ() + 0.5);
        Vec3 start = body.position();
        ToolRun[] follow = new ToolRun[1];
        helper.onEachTick(() -> {
            if (follow[0] != null) {
                result.fixtureCheck(owner.position().distanceToSqr(ownerTarget) <= 0.25 * 0.25,
                        "owner fixture did not remain at the follow target");
                if (horizontalSquared(body.position(), start) >= 0.25 * 0.25) result.mark("body_change");
            }
        });
        helper.startSequence()
                // 等登录定位完成,用服务器传送同时更新身体与假客户端的待确认位置。
                .thenExecuteAfter(20, () -> owner.teleportTo(ownerTarget.x, ownerTarget.y, ownerTarget.z))
                .thenExecuteAfter(5, () -> {
                    result.fixtureCheck(owner.position().distanceToSqr(ownerTarget) <= 0.25 * 0.25,
                            "owner fixture teleport did not persist after login settled");
                    result.fixtureCheck(body.distanceTo(owner) >= 10,
                            "owner fixture is too close to exercise follow movement");
                    result.metric("initial_owner_distance_blocks", body.distanceTo(owner));
                    result.trigger();
                    follow[0] = command(body, "move follow");
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(follow[0].task() != null, "follow was not accepted: " + follow[0].reply());
                    helper.assertTrue(result.marked("body_change") && body.distanceTo(owner) <= 4.5,
                            "body has not physically followed the owner");
                    helper.assertTrue(!follow[0].done(), "follow ended instead of remaining active");
                    result.metric("final_owner_distance_blocks", body.distanceTo(owner));
                })
                .thenSucceed();
    }

    /** task_stop 叫停已经移动的 goto,停稳后四十刻内不得重新移动或恢复旧任务。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_benchmark")
    public static void task_stop_stops_movement(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-task-stop");
        NumenPlayer body = result.body(spawnAt(helper, "eval_task_stop", new BlockPos(2, 2, 2), false));
        Vec3 start = body.position();
        ToolRun walk = go(helper, body, new BlockPos(14, 2, 14));
        QuietBody quiet = new QuietBody(helper, body, result);
        helper.onEachTick(quiet::sample);
        helper.startSequence()
                .thenWaitUntil(() -> moving(helper, body, start, walk))
                .thenExecute(() -> {
                    result.trigger();
                    ToolRun stop = call(body, "task_stop", args());
                    helper.assertTrue(stop.succeeded(), "task_stop failed: " + stop.reply());
                    quiet.begin();
                })
                .thenWaitUntil(() -> {
                    cancelled(helper, body, walk, TaskRecord.StopCause.TASK_STOP);
                    helper.assertTrue(quiet.heldFor(40), "body has not remained still for 40 ticks");
                    helper.assertTrue(event(body, "task_finished", "stopped"), "stopped task event was not emitted");
                })
                .thenSucceed();
    }

    /** 新 goto 取代已经走起来的旧目标:旧活按 REPLACED 结算,身体实际回到起点。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_benchmark")
    public static void replacement_returns_to_start(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-replace-return");
        NumenPlayer body = result.body(spawnAt(helper, "eval_return", new BlockPos(2, 2, 2), false));
        Vec3 home = body.position();
        ToolRun walk = go(helper, body, new BlockPos(14, 2, 14));
        ToolRun[] back = new ToolRun[1];
        double[] turnDistance = {0};
        helper.onEachTick(() -> {
            if (back[0] != null && Math.sqrt(horizontalSquared(body.position(), home)) <= turnDistance[0] - 0.25) {
                result.mark("body_change");
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    moving(helper, body, home, walk);
                    helper.assertTrue(horizontalSquared(body.position(), home) >= 16, "body has not walked four blocks");
                })
                .thenExecute(() -> {
                    result.trigger();
                    turnDistance[0] = Math.sqrt(horizontalSquared(body.position(), home));
                    back[0] = go(helper, body, new BlockPos(2, 2, 2));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(walk.task().getState() == TaskState.CANCELLED
                                    && walk.task().getStopCause() == TaskRecord.StopCause.REPLACED,
                            "old task was not replaced");
                    helper.assertTrue(back[0].succeeded() && horizontalSquared(body.position(), home) <= 4,
                            "body has not returned home: " + back[0].outcome());
                    helper.assertTrue(result.marked("body_change"), "replacement did not turn the body homeward");
                    helper.assertTrue(event(body, "task_finished", "replaced"), "replacement event was not emitted");
                    result.metric("final_home_distance_blocks", Math.sqrt(horizontalSquared(body.position(), home)));
                })
                .thenSucceed();
    }

    /** 表与身体独立:走路途中到点,随后仍能到达原目标,表只发一次。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_benchmark")
    public static void timer_and_walking_run_together(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-timer-parallel");
        NumenPlayer body = result.body(spawnAt(helper, "eval_timer", new BlockPos(2, 2, 2), false));
        Vec3 start = body.position();
        ToolRun[] walk = new ToolRun[1];
        helper.onEachTick(() -> {
            if (walk[0] != null && horizontalSquared(body.position(), start) >= 0.25 * 0.25) {
                result.mark("body_change");
            }
            if (walk[0] != null && !result.marked("timer_event") && event(body, "timer", "eval furnace ready")) {
                helper.assertTrue(CompanionTickDispatcher.currentTaskFor(body.getUUID()) == walk[0].task(),
                        "timer did not fire while independent walking was pending");
                result.mark("timer_event");
            }
        });
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    result.trigger();
                    ToolRun timer = command(body, "task timer 1 eval furnace ready");
                    helper.assertTrue(timer.succeeded(), "timer rejected: " + timer.reply());
                    walk[0] = go(helper, body, new BlockPos(14, 2, 14));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(result.marked("timer_event") && result.marked("body_change"),
                            "timer event or physical progress is missing");
                    helper.assertTrue(walk[0].succeeded()
                                    && body.blockPosition().distSqr(helper.absolutePos(new BlockPos(14, 2, 14))) <= 4,
                            "independent walking did not complete: " + walk[0].outcome());
                    helper.assertTrue(EventOutbox.get(helper.getLevel().getServer()).peek(body.getUUID()).entries()
                                    .stream().filter(e -> e.type().equals("timer") && e.text().contains("eval furnace ready")).count() == 1,
                            "timer fired more than once");
                    helper.assertTrue(TimerRegistry.get(helper.getLevel().getServer()).list(body.getUUID()).isEmpty(),
                            "expired timer remains pending");
                })
                .thenSucceed();
    }

    /** 行走中遭一次真实伤害:本能实际击中来袭者,上报本能,未取消的路继续走完。 */
    @GameTest(template = "floor16", timeoutTicks = 1800, batch = "numen_benchmark")
    public static void danger_preempts_moving_body(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-combat-preemption");
        NumenPlayer body = result.body(armedCompanion(helper, new BlockPos(2, 2, 2)));
        Vec3 start = body.position();
        ToolRun[] walk = new ToolRun[1];
        Zombie[] threat = new Zombie[1];
        helper.startSequence()
                // 原版出生保护结束后才投递伤害,否则观察到的是无敌期而非本能响应。
                .thenExecuteAfter(70, () -> walk[0] = go(helper, body, new BlockPos(14, 2, 14)))
                .thenWaitUntil(() -> moving(helper, body, start, walk[0]))
                .thenExecute(() -> {
                    result.trigger();
                    threat[0] = attackFixture(helper, body, result);
                })
                .thenWaitUntil(() -> hitBack(helper, body, threat[0], result))
                .thenExecute(() -> threat[0].discard())
                .thenWaitUntil(() -> {
                    helper.assertTrue(event(body, "reflex", "mob_defense"), "defense was not reported");
                    helper.assertTrue(walk[0].succeeded()
                                    && body.blockPosition().distSqr(helper.absolutePos(new BlockPos(14, 2, 14))) <= 4,
                            "uncancelled walking did not complete after defense: " + walk[0].outcome());
                    helper.assertTrue(body.isAlive(), "body died during the defense");
                })
                .thenSucceed();
    }

    /** 停止键先撤任务,随后仍可自卫;危险结束后旧任务不得复活。 */
    @GameTest(template = "floor16", timeoutTicks = 1800, batch = "numen_benchmark")
    public static void owner_stop_allows_defense_without_resuming_work(GameTestHelper helper) {
        Observation result = new Observation(helper, "body-owner-stop-danger");
        NumenPlayer body = result.body(armedCompanion(helper, new BlockPos(2, 2, 2)));
        Vec3 start = body.position();
        ToolRun[] walk = new ToolRun[1];
        Zombie[] threat = new Zombie[1];
        QuietBody quiet = new QuietBody(helper, body, result);
        boolean[] stoppedConfirmed = {false};
        helper.onEachTick(() -> {
            quiet.sample();
            if (stoppedConfirmed[0]) cancelled(helper, body, walk[0], TaskRecord.StopCause.OWNER);
        });
        helper.startSequence()
                .thenExecuteAfter(70, () -> walk[0] = go(helper, body, new BlockPos(14, 2, 14)))
                .thenWaitUntil(() -> moving(helper, body, start, walk[0]))
                .thenExecute(() -> {
                    result.trigger();
                    CompanionTickDispatcher.cancelFor(body);
                    quiet.begin();
                })
                .thenWaitUntil(() -> {
                    cancelled(helper, body, walk[0], TaskRecord.StopCause.OWNER);
                    helper.assertTrue(quiet.heldFor(40), "owner Stop has not held the body still for 40 ticks");
                })
                .thenExecute(() -> {
                    stoppedConfirmed[0] = true;
                    quiet.active = false;
                    result.mark("danger_trigger");
                    threat[0] = attackFixture(helper, body, result);
                })
                .thenWaitUntil(() -> hitBack(helper, body, threat[0], result))
                .thenExecute(() -> threat[0].discard())
                .thenWaitUntil(() -> helper.assertTrue(event(body, "reflex", "mob_defense"), "defense was not reported"))
                .thenExecute(quiet::begin)
                .thenWaitUntil(() -> {
                    cancelled(helper, body, walk[0], TaskRecord.StopCause.OWNER);
                    helper.assertTrue(quiet.heldFor(40), "body resumed movement after the threat ended");
                    helper.assertTrue(body.isAlive(), "body died after Stop");
                    result.metric("no_resume_observation_ticks", 40);
                })
                .thenSucceed();
    }

    private static ToolRun go(GameTestHelper helper, NumenPlayer body, BlockPos rel) {
        BlockPos target = helper.absolutePos(rel);
        return call(body, "move_goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));
    }

    private static void moving(GameTestHelper helper, NumenPlayer body, Vec3 start, ToolRun walk) {
        helper.assertTrue(walk != null && walk.task() != null, "goto was not accepted");
        helper.assertTrue(walk.task().getState() == TaskState.RUNNING
                        && horizontalSquared(body.position(), start) >= 4,
                "goto has not physically moved two blocks while running");
    }

    private static void cancelled(GameTestHelper helper, NumenPlayer body, ToolRun walk, TaskRecord.StopCause cause) {
        helper.assertTrue(walk.task().getState() == TaskState.CANCELLED && walk.task().getStopCause() == cause,
                "task was not cancelled with " + cause);
        helper.assertTrue(CompanionTickDispatcher.currentTaskFor(body.getUUID()) == null, "old task resumed");
    }

    /** 固定敌人 AI 避免随机走位;真实伤害入口与被测身体的战斗执行仍照常运行。 */
    private static Zombie attackFixture(GameTestHelper helper, NumenPlayer body, Observation result) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        result.fixtureCheck(zombie != null, "zombie fixture did not spawn");
        result.entities.add(zombie);
        zombie.moveTo(body.getX() + 1.5, body.getY(), body.getZ());
        zombie.setNoAi(true);
        zombie.setTarget(body);
        helper.getLevel().addFreshEntity(zombie);
        float before = body.getHealth();
        body.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1);
        result.fixtureCheck(body.getHealth() < before && body.getLastHurtByMob() == zombie,
                "fixture did not deliver actual attributed damage");
        return zombie;
    }

    private static void hitBack(GameTestHelper helper, NumenPlayer body, Zombie zombie, Observation result) {
        helper.assertTrue(body.isAlive(), "body died instead of defending itself");
        helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth() && zombie.getLastHurtByMob() == body,
                "body has not physically hit the attacker");
        if (result.marked("danger_trigger")) {
            result.metric("defense_ticks", result.elapsedTicks() - result.metrics.get("danger_trigger_ticks").getAsLong());
            result.metric("defense_wall_ms", result.elapsedMs() - result.metrics.get("danger_trigger_wall_ms").getAsDouble());
        } else {
            result.mark("body_change");
        }
    }

    private static boolean event(NumenPlayer body, String type, String text) {
        return EventOutbox.get(body.getServer()).peek(body.getUUID()).entries().stream()
                .anyMatch(e -> e.type().equals(type) && e.text().contains(text));
    }

    private static double horizontalSquared(Vec3 a, Vec3 b) {
        double x = a.x - b.x;
        double z = a.z - b.z;
        return x * x + z * z;
    }

    /** 连续三刻每刻位移不足 0.01 格才算停稳;之后整个观察窗不得离开锚点 0.15 格。 */
    private static final class QuietBody {
        private final GameTestHelper helper;
        private final NumenPlayer body;
        private final Observation result;
        private boolean active;
        private Vec3 previous;
        private Vec3 anchor;
        private int quietTicks;
        private long quietSince;

        QuietBody(GameTestHelper helper, NumenPlayer body, Observation result) {
            this.helper = helper;
            this.body = body;
            this.result = result;
        }

        void begin() {
            active = true;
            previous = body.position();
            anchor = null;
            quietTicks = 0;
        }

        void sample() {
            if (!active) return;
            if (anchor == null) {
                quietTicks = horizontalSquared(body.position(), previous) < 0.0001 ? quietTicks + 1 : 0;
                previous = body.position();
                if (quietTicks >= 3) {
                    anchor = body.position();
                    quietSince = helper.getLevel().getGameTime();
                    result.mark("body_change");
                }
            } else {
                helper.assertTrue(horizontalSquared(body.position(), anchor) <= 0.15 * 0.15,
                        "body moved again after settling");
                helper.assertTrue(CompanionTickDispatcher.currentTaskFor(body.getUUID()) == null,
                        "a task reappeared after Stop");
            }
        }

        boolean heldFor(int ticks) {
            return anchor != null && helper.getLevel().getGameTime() - quietSince >= ticks;
        }
    }

    /** 原版终态回调也覆盖断言异常与超时;结果写出后统一清场,失败样例不会污染下一批。 */
    private static final class Observation implements GameTestListener {
        private final GameTestHelper helper;
        private final String caseId;
        private final long startedNs = System.nanoTime();
        private final JsonObject metrics = new JsonObject();
        private final JsonArray initialBodies = new JsonArray();
        private final List<NumenPlayer> bodies = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();
        private long triggerTick;
        private long triggerNs;
        private boolean triggered;
        private boolean fixtureFailed;

        Observation(GameTestHelper helper, String caseId) {
            this.helper = helper;
            this.caseId = caseId;
            helper.testInfo.addListener(this);
        }

        NumenPlayer body(NumenPlayer body) {
            bodies.add(body);
            initialBodies.add(snapshot(body));
            return body;
        }

        void trigger() {
            triggerTick = helper.getLevel().getGameTime();
            triggerNs = System.nanoTime();
            triggered = true;
        }

        void fixtureCheck(boolean condition, String reason) {
            if (!condition) fixtureFailed = true;
            helper.assertTrue(condition, reason);
        }

        long elapsedTicks() { return helper.getLevel().getGameTime() - triggerTick; }
        double elapsedMs() { return (System.nanoTime() - triggerNs) / 1_000_000.0; }
        boolean marked(String name) { return metrics.has(name + "_ticks"); }
        void metric(String name, Number value) { metrics.addProperty(name, value); }

        void mark(String name) {
            if (marked(name)) return;
            metric(name + "_ticks", elapsedTicks());
            metric(name + "_wall_ms", elapsedMs());
        }

        @Override public void testStructureLoaded(GameTestInfo info) {}
        @Override public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) {}
        @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { finish(true, null); }
        @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { finish(false, info.getError()); }

        private void finish(boolean success, Throwable error) {
            metric("total_ticks", helper.getTick());
            metric("total_wall_ms", (System.nanoTime() - startedNs) / 1_000_000.0);
            JsonObject row = new JsonObject();
            row.addProperty("case_id", caseId);
            row.addProperty("evaluation_version", "bot-v1.2");
            row.addProperty("layer", "body");
            row.addProperty("status", success ? "ok" : !triggered || fixtureFailed ? "infra_error" : "system_failure");
            row.addProperty("success", success);
            row.addProperty("reason", error == null ? "GameTest world assertions passed" : error.toString());
            row.add("metrics", metrics);
            JsonObject evidence = new JsonObject();
            evidence.addProperty("fixture_scope", "handcrafted GameTest world; registered tools and server body execution");
            evidence.addProperty("model_used", false);
            evidence.addProperty("network_used", false);
            evidence.addProperty("java_runtime", System.getProperty("java.runtime.version"));
            evidence.addProperty("tick_clock", "unpaced Minecraft GameTest simulation ticks");
            evidence.addProperty("wall_time_scope", "unpaced headless GameTest process diagnostic; not player-observed realtime");
            evidence.addProperty("trigger_established", triggered);
            evidence.addProperty("fixture_failed", fixtureFailed);
            evidence.add("initial_bodies", initialBodies);
            JsonArray finalBodies = new JsonArray();
            bodies.forEach(body -> finalBodies.add(snapshot(body)));
            evidence.add("final_bodies", finalBodies);
            row.add("evidence", evidence);
            entities.forEach(Entity::discard);
            for (NumenPlayer body : bodies) {
                CompanionFactory.despawn(helper.getLevel().getServer(), body);
                EventOutbox.get(helper.getLevel().getServer()).forget(body.getUUID());
            }
            String output = System.getProperty("numen.eval.output");
            if (output == null || output.isBlank()) return;
            try {
                Path directory = Path.of(output);
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("body.jsonl"), row + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write body benchmark result", e);
            }
        }

        private static JsonObject snapshot(NumenPlayer body) {
            JsonObject state = new JsonObject();
            state.addProperty("uuid", body.getUUID().toString());
            JsonObject position = new JsonObject();
            position.addProperty("x", body.getX());
            position.addProperty("y", body.getY());
            position.addProperty("z", body.getZ());
            state.add("position", position);
            state.addProperty("health", body.getHealth());
            state.addProperty("alive", body.isAlive());
            TaskRecord current = CompanionTickDispatcher.currentTaskFor(body.getUUID());
            if (current == null) {
                state.add("current_task", JsonNull.INSTANCE);
            } else {
                JsonObject task = new JsonObject();
                task.addProperty("id", current.publicId());
                task.addProperty("tool", current.getToolName());
                task.addProperty("state", current.getState().name());
                state.add("current_task", task);
            }
            return state;
        }
    }
}
