package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionQueueTest {

    private static final class FakeTools implements ToolPort {
        final List<LlmToolCall> received = new ArrayList<>();
        Sink sink;

        @Override
        public void run(List<LlmToolCall> calls, Sink sink) {
            this.sink = sink;
            received.addAll(calls);
            calls.forEach(sink::started);
        }

        @Override
        public List<String> cancel(boolean stopBody) {
            return List.of();
        }

        void finish(String id) {
            sink.finished(new LlmToolCall(id, "mine", "{}"), "{\"success\":true}");
            sink.settled();
        }
    }

    private static final class SinkLog implements ToolPort.Sink {
        final List<String> started = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        int settled;

        @Override
        public void started(LlmToolCall call) {
            started.add(call.id());
        }

        @Override
        public void finished(LlmToolCall call, String resultJson) {
            finished.add(call.id() + "=" + resultJson);
        }

        @Override
        public void settled() {
            settled++;
        }
    }

    @Test
    void recordsEveryActionInOrder() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.harnessDefault());
        SinkLog log = new SinkLog();

        queue.run(List.of(new LlmToolCall("c1", "mine", "{\"block\":\"dirt\"}")), log);
        tools.sink.finished(new LlmToolCall("c1", "mine", "{}"), "{\"success\":true}");
        tools.sink.settled();

        assertEquals(1, queue.ledger().size());
        ActionRecord r = queue.ledger().get(0);
        assertEquals("mine", r.tool());
        assertTrue(r.success());
        assertFalse(r.denied());
        assertEquals(1, log.settled);
    }

    @Test
    void aCommandThatCouldFakeTheGoalNeverReachesTheToolLayer() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.harnessDefault());
        SinkLog log = new SinkLog();

        LlmToolCall cheat = new LlmToolCall("c1", "command",
                "{\"command\":\"/setblock 0 -60 0 minecraft:diamond_block\"}");
        queue.run(List.of(cheat), log);

        assertTrue(tools.received.isEmpty(), "被拦的调用不许进工具层");
        assertEquals(1, queue.ledger().size());
        assertTrue(queue.ledger().get(0).denied());
        assertFalse(queue.ledger().get(0).success());
        assertTrue(log.finished.get(0).contains("acceptance whitelist"));
        assertEquals(1, log.settled);
    }

    @Test
    void aNormalToolPassesTheHarnessDefault() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.harnessDefault());
        SinkLog log = new SinkLog();

        queue.run(List.of(new LlmToolCall("c1", "mine", "{}")), log);

        assertEquals(1, tools.received.size());
        tools.finish("c1");
        assertEquals(1, queue.ledger().size());
        assertFalse(queue.ledger().get(0).denied());
    }

    @Test
    void theHarnessDefaultRemovesTheCommandToolEntirely() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.harnessDefault());
        SinkLog log = new SinkLog();

        queue.run(List.of(new LlmToolCall("c1", "command", "{\"command\":\"/help give\"}")), log);

        assertTrue(tools.received.isEmpty());
        assertTrue(queue.ledger().get(0).denied());
        assertTrue(queue.ledger().get(0).result().contains("万能口子"));
    }

    @Test
    void anAllowlistCanDeliberatelyExposeOnlySomeTools() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.allowOnly(Set.of("mine", "craft")));
        SinkLog log = new SinkLog();

        queue.run(List.of(
                new LlmToolCall("ok", "mine", "{}"),
                new LlmToolCall("no", "command", "{\"command\":\"/help give\"}")), log);

        assertEquals(1, tools.received.size());
        assertEquals("ok", tools.received.get(0).id());
        assertTrue(queue.ledger().stream().anyMatch(ActionRecord::denied));
    }

    @Test
    void mixedBatchDeniesOnlyTheCheatingCall() {
        FakeTools tools = new FakeTools();
        ActionQueue queue = new ActionQueue(tools, Whitelist.harnessDefault());
        SinkLog log = new SinkLog();

        queue.run(List.of(
                new LlmToolCall("ok", "mine", "{}"),
                new LlmToolCall("bad", "command", "{\"command\":\"/give @s diamond 64\"}")), log);

        assertEquals(1, tools.received.size());
        assertEquals("ok", tools.received.get(0).id());
        tools.finish("ok");
        assertEquals(2, queue.ledger().size());
        assertTrue(queue.ledger().stream().anyMatch(ActionRecord::denied));
        assertEquals(1, log.settled);
    }
}
