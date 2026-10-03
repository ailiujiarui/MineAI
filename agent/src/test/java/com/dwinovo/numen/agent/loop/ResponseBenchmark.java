package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 AgentLoop 的响应契约评测。模型、工具和时钟来自 LoopHarness；注入的等待不是实测模型或身体延迟。
 * 用户要求的行为与契约断言由 success 计分；夹具异常另记为 infra_error，保留逐例证据。
 */
@Tag("benchmark")
class ResponseBenchmark extends LoopHarness {

    private static Path output;

    @BeforeAll
    static void prepareReport() throws IOException {
        output = Path.of(System.getProperty("numen.eval.output", "build/benchmark"))
                .resolve("response.jsonl");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, "", StandardCharsets.UTF_8);
    }

    @Test
    void idleFollow() throws IOException {
        measure("response.idle_follow", "空闲时说‘跟我走’", row -> {
            long submitted = host.now;
            ownerSays("跟我走");
            assertEquals(1, model.calls.size(), "主人输入应在本次 push 中启动模型请求");
            assertTrue(model.last().lastUser().contains("跟我走"));
            row.metrics.addProperty("fixture_prompt_to_model_request_ms", host.now - submitted);

            host.now += 800;
            model.last().callTools(call("follow-1", "follow"));
            assertEquals("follow", eventsOf(LoopEvent.ToolStarted.class).getFirst().call().name());
            row.metrics.addProperty("fixture_scripted_model_wait_ms", 800);
            row.metrics.addProperty("fixture_prompt_to_tool_dispatch_ms", host.now - submitted);
            row.evidence.addProperty("model_choice", "scripted follow; not scored as model understanding");
            row.pass("空闲输入立即进入真实循环；脚本回复交给工具端口。真实跟随和模型选择另测。");
        });
    }

    @Test
    void steerDuringModel() throws IOException {
        measure("response.steer_during_model", "模型还在想‘挖矿’，主人改成‘先回来’", row -> {
            ownerSays("去挖矿");
            Call old = model.last();
            host.now += 100;
            long revised = host.now;
            ownerSays("先回来，不要挖了");
            boolean oldCancelledAtRevision = old.cancel().isCancelled();

            host.now += 1100;
            old.callTools(call("stale-mine", "mine"));
            int staleDispatches = (int) eventsOf(LoopEvent.ToolStarted.class).stream()
                    .filter(e -> e.call().id().equals("stale-mine")).count();
            row.metrics.addProperty("stale_tool_dispatches", staleDispatches);
            row.evidence.addProperty("old_model_cancelled_at_revision", oldCancelledAtRevision);
            row.evidence.addProperty("old_reply", "scripted mine tool after revision");

            // 让真实内核有机会走到它自己的插话边界，不把工具端口的等待算成内核耗时。
            if (tools.batch.stream().anyMatch(c -> c.id().equals("stale-mine"))) {
                host.now += 250;
                tools.finish("stale-mine");
            }
            boolean latestDelivered = model.calls.size() >= 2
                    && model.last().lastUser().contains("先回来，不要挖了");
            assertTrue(latestDelivered, "改口必须保留并最终进入模型请求");
            row.metrics.addProperty("fixture_remaining_model_wait_ms", 1100);
            row.metrics.addProperty("fixture_old_tool_wait_ms", staleDispatches > 0 ? 250 : 0);
            row.metrics.addProperty("fixture_revision_to_latest_request_ms", row.latestModelRequestAt - revised);
            row.evidence.addProperty("latest_instruction_delivered", latestDelivered);
            row.score(staleDispatches == 0,
                    staleDispatches == 0 ? "改口后旧回复没有派发旧工具。"
                            : "改口后旧模型回复仍派发 mine；不满足‘旧指令不得继续影响身体’。");
        });
    }

    @Test
    void steerDuringTool() throws IOException {
        measure("response.steer_during_tool", "同步工具尚未结算时，主人补充‘先回来’", row -> {
            ownerSays("处理眼前的方块");
            model.last().callTools(call("sync-1", "interact_at"));
            host.now += 100;
            long revised = host.now;
            ownerSays("先回来");
            assertEquals(1, model.calls.size(), "同步工具的边界尚未到达");
            assertEquals(1, inbox.count(EventTypes.QUERY), "插话仍在队列中");

            host.now += 700;
            tools.finish("sync-1");
            assertEquals(2, model.calls.size());
            assertTrue(model.last().lastUser().contains("先回来"));
            row.metrics.addProperty("fixture_remaining_sync_tool_wait_ms", 700);
            row.metrics.addProperty("fixture_revision_to_latest_request_ms", row.latestModelRequestAt - revised);
            row.evidence.addProperty("tool_cancel_requested", !tools.cancels.isEmpty());
            row.pass("插话在已派发同步工具结算后进入下一模型请求；不包含后台任务替换的身体验证。");
        });
    }

    @Test
    void stopButtonDuringModel() throws IOException {
        measure("response.stop_button_during_model", "模型流式回复中点击停止", row -> {
            ownerSays("去挖矿");
            Call old = model.last();
            old.stream("我准备");
            host.now += 100;
            long stopped = host.now;
            loop.halt(HaltReason.OWNER_STOP);
            assertTrue(old.cancel().isCancelled());
            assertEquals(Hold.OWNER_STOP, loop.hold());
            assertEquals(java.util.List.of(true), tools.cancels);
            int afterStop = events.size();

            host.now += 500;
            old.stream("继续挖矿");
            old.callTools(call("late-mine", "mine"));
            assertEquals(afterStop, events.size(), "停止后迟到的回复和工具必须作废");
            worldEvent("任务已停止", true);
            assertEquals(1, model.calls.size(), "世界急件不能自动恢复主人叫停的工作");
            row.metrics.addProperty("fixture_stop_to_cancel_request_ms", 0);
            row.metrics.addProperty("fixture_observation_window_ms", host.now - stopped);
            row.metrics.addProperty("late_tool_dispatches", tools.batch.size());
            row.evidence.addProperty("hold", loop.hold().name());
            row.evidence.addProperty("body_stop_requested", tools.cancels.contains(true));
            row.pass("停止同步取消模型并请求身体停止；迟到工具作废，世界事件不会自动续跑。");
        });
    }

    @Test
    void stopButtonDuringTool() throws IOException {
        measure("response.stop_button_during_tool", "工具调用尚未结算时点击停止", row -> {
            ownerSays("去挖矿");
            model.last().callTools(call("running-mine", "mine"));
            ToolPort.Sink old = tools.sink;
            host.now += 100;
            loop.halt(HaltReason.OWNER_STOP);
            assertEquals(java.util.List.of(true), tools.cancels);
            assertEquals(Hold.OWNER_STOP, loop.hold());
            assertTrue(tools.batch.isEmpty());

            host.now += 400;
            tools.finish(old, "running-mine", "{\"success\":true}");
            assertFalse(transcript.snapshot().stream().anyMatch(m -> m instanceof ConvoState.Msg.Tool t
                    && t.toolCallId().equals("running-mine")), "停止后迟到结果不应复活回合");
            assertEquals(1, model.calls.size());
            row.metrics.addProperty("fixture_stop_to_cancel_request_ms", 0);
            row.metrics.addProperty("fixture_late_tool_result_wait_ms", 400);
            row.evidence.addProperty("body_stop_requested", tools.cancels.contains(true));
            row.evidence.addProperty("external_tool_isolation", "not measured: FakeTools is not the production dispatcher");
            row.pass("停止放弃本轮工具、请求身体取消并拒绝迟到结果；实际身体、自卫及外接调用隔离需集成评测。");
        });
    }

    @Test
    void naturalStop() throws IOException {
        measure("response.natural_stop", "身体在后台挖矿，主人说‘停下’", row -> {
            host.bodyTask = true;
            ownerSays("停下");
            boolean immediateBodyStop = tools.cancels.contains(true);
            boolean held = loop.hold() == Hold.OWNER_STOP;
            row.evidence.addProperty("body_stop_requested_before_model_reply", immediateBodyStop);
            row.evidence.addProperty("owner_stop_hold_before_model_reply", held);
            row.metrics.addProperty("model_requests_before_body_stop", model.calls.size());
            if (!model.calls.isEmpty()) {
                assertTrue(model.last().lastUser().contains("停下"), "停止请求不能丢失");
                host.now += 600;
                model.last().callTools(call("stop-1", "task_stop"));
                row.metrics.addProperty("fixture_scripted_model_wait_ms", 600);
                row.metrics.addProperty("fixture_prompt_to_task_stop_dispatch_ms", 600);
                row.evidence.addProperty("model_choice", "scripted task_stop; live understanding unmeasured");
            }
            row.score(immediateBodyStop && held,
                    immediateBodyStop && held ? "自然语言停止在模型回复前请求取消并阻止自动续跑。"
                            : "停下作为普通 QUERY 进入模型，没有立即请求身体取消或进入 OWNER_STOP；真实模型理解和本能保留尚未测量。");
        });
    }

    private static LlmToolCall call(String id, String name) {
        return new LlmToolCall(id, name, "{}");
    }

    private void measure(String id, String scenario, Scenario body) throws IOException {
        Row row = new Row(id, scenario);
        loop.subscribe(event -> {
            JsonObject observed = new JsonObject();
            observed.addProperty("event", event.getClass().getSimpleName());
            observed.addProperty("fixture_offset_ms", host.now - T0);
            if (event instanceof LoopEvent.TurnStarted) {
                row.latestModelRequestAt = host.now;
            }
            if (event instanceof LoopEvent.ToolStarted tool) {
                observed.addProperty("tool_id", tool.call().id());
                observed.addProperty("tool_name", tool.call().name());
            }
            row.trace.add(observed);
        });
        long started = System.nanoTime();
        try {
            body.run(row);
        } catch (AssertionError failure) {
            row.score(false, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        } catch (RuntimeException failure) {
            row.json.addProperty("status", "infra_error");
            row.json.add("success", JsonNull.INSTANCE);
            row.json.addProperty("reason", failure.toString());
            throw failure;
        } finally {
            row.metrics.addProperty("local_execution_ms", (System.nanoTime() - started) / 1_000_000.0);
            Files.writeString(output, row.json + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
    }

    private interface Scenario {
        void run(Row row);
    }

    private static final class Row {
        final JsonObject json = new JsonObject();
        final JsonObject metrics = new JsonObject();
        final JsonObject evidence = new JsonObject();
        final JsonArray trace = new JsonArray();
        long latestModelRequestAt;

        Row(String id, String scenario) {
            json.addProperty("case_id", id);
            json.addProperty("evaluation_version", "bot-v1.2");
            json.addProperty("split", "dev");
            json.addProperty("rep", 0);
            json.addProperty("layer", "response");
            json.addProperty("status", "ok");
            json.add("success", JsonNull.INSTANCE);
            json.addProperty("reason", "Scenario did not reach a verdict");
            json.addProperty("scenario", scenario);
            json.addProperty("entrypoint", "AgentLoop.push / AgentLoop.halt");
            json.addProperty("measurement", "real loop, scripted model/tool ports, virtual fixture clock");
            metrics.add("live_model_latency_ms", JsonNull.INSTANCE);
            metrics.add("physical_body_latency_ms", JsonNull.INSTANCE);
            evidence.addProperty("self_defense_preserved", "not measured: no Minecraft body in this layer");
            evidence.addProperty("java_runtime", System.getProperty("java.runtime.version"));
            json.add("metrics", metrics);
            json.add("evidence", evidence);
            json.add("trace", trace);
        }

        void pass(String reason) {
            score(true, reason);
        }

        void score(boolean success, String reason) {
            json.addProperty("success", success);
            json.addProperty("reason", reason);
        }
    }
}
