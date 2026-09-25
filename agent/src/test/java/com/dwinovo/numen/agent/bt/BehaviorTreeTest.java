package com.dwinovo.numen.agent.bt;

import com.dwinovo.numen.agent.control.DecisionNode;
import com.dwinovo.numen.agent.control.LlmNode;
import com.dwinovo.numen.agent.control.PlannerPort;
import com.dwinovo.numen.agent.decision.DecisionQuestion;
import com.dwinovo.numen.agent.decision.DecisionResult;
import com.dwinovo.numen.agent.decision.MockDecisionProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BehaviorTreeTest {

    private static Node.Status tick(BehaviorTree tree, BtContext ctx, long t) {
        ctx.beginTick(t, () -> "state");
        return tree.tick(ctx);
    }

    private static ActionNode ok(String name) {
        return new ActionNode(name, c -> Node.Status.SUCCESS);
    }

    private static ActionNode fail(String name) {
        return new ActionNode(name, c -> Node.Status.FAILURE);
    }

    @Test
    void sequenceRunsLeftToRightAndStopsOnFailure() {
        BtContext ctx = new BtContext();
        BehaviorTree allOk = new BehaviorTree("s", new Sequence(ok("a"), ok("b")));
        assertEquals(Node.Status.SUCCESS, tick(allOk, ctx, 1));

        BehaviorTree stops = new BehaviorTree("s", new Sequence(ok("a"), fail("b"), ok("c")));
        assertEquals(Node.Status.FAILURE, tick(stops, ctx, 1));
    }

    @Test
    void selectorFallsBackToTheNextChild() {
        BtContext ctx = new BtContext();
        BehaviorTree tree = new BehaviorTree("sel", new Selector(fail("a"), fail("b"), ok("c")));
        assertEquals(Node.Status.SUCCESS, tick(tree, ctx, 1));
    }

    @Test
    void retryGivesTheChildSeveralAttempts() {
        BtContext ctx = new BtContext();
        int[] fails = {2};
        Node flaky = new ActionNode("flaky", c -> fails[0]-- > 0 ? Node.Status.FAILURE : Node.Status.SUCCESS);
        BehaviorTree tree = new BehaviorTree("r", new Retry(flaky, 3));

        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 1));
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 2));
        assertEquals(Node.Status.SUCCESS, tick(tree, ctx, 3));
    }

    @Test
    void retryFailsOnceAttemptsAreExhausted() {
        BtContext ctx = new BtContext();
        BehaviorTree tree = new BehaviorTree("r", new Retry(fail("never"), 2));
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 1));
        assertEquals(Node.Status.FAILURE, tick(tree, ctx, 2));
    }

    @Test
    void timeoutFailsALongRunningSubtree() {
        BtContext ctx = new BtContext();
        BehaviorTree tree = new BehaviorTree("t", new Timeout(new ActionNode("spin", c -> Node.Status.RUNNING), 3));
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 0));
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 1));
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 2));
        assertEquals(Node.Status.FAILURE, tick(tree, ctx, 3));
    }

    @Test
    void decisionNodeRoutesByTheTypedAnswer() {
        MockDecisionProvider decisions = new MockDecisionProvider();
        decisions.enqueue(new DecisionResult(JsonParser.parseString(
                "{\"pick\":{\"choice\":\"a\",\"confidence\":0.9}}").getAsJsonObject(), "mock"));

        JsonObject questions = new JsonObject();
        questions.add("pick", DecisionQuestion.choice("which?", Map.of("a", "left", "b", "right")));
        DecisionNode node = new DecisionNode(decisions, questions, "pick",
                Map.of("a", ok("a"), "b", fail("b")), fail("fallback"));
        BehaviorTree tree = new BehaviorTree("d", node);

        BtContext ctx = new BtContext();
        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 1));   // decision in flight
        assertEquals(Node.Status.SUCCESS, tick(tree, ctx, 2));  // branch "a" succeeds
        assertEquals("a", node.chosen());
    }

    @Test
    void llmNodeRunsOnlyAtItsExplicitPosition() {
        BtContext ctx = new BtContext();
        PlannerPort planner = request -> CompletableFuture.completedFuture("PLAN");
        LlmNode node = new LlmNode(planner, "plan the goal",
                (text, c) -> text.contains("PLAN") ? Node.Status.SUCCESS : Node.Status.FAILURE);
        BehaviorTree tree = new BehaviorTree("llm", node);

        assertEquals(Node.Status.RUNNING, tick(tree, ctx, 1));
        assertEquals(Node.Status.SUCCESS, tick(tree, ctx, 2));
    }
}
