package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.Condition;
import com.dwinovo.numen.agent.bt.Node;
import com.dwinovo.numen.agent.bt.Sequence;
import com.dwinovo.numen.agent.decision.DecisionQuestion;
import com.dwinovo.numen.agent.decision.DecisionResult;
import com.dwinovo.numen.agent.decision.MockDecisionProvider;
import com.dwinovo.numen.agent.utility.BehaviorOption;
import com.dwinovo.numen.agent.utility.Consideration;
import com.dwinovo.numen.agent.utility.ControlState;
import com.dwinovo.numen.agent.utility.UtilityArbiter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四层拧在一起跑一遍:Utility 选行为 → 行为树执行 → 树上挂 JEV 判断节点和 LLM 边界节点 →
 * 成功/失败/抢占都以事件报出。全纯 JVM,不需要服务端。
 */
class AgentControllerTest {

    private final MockDecisionProvider decisions = new MockDecisionProvider();
    private final PlannerPort planner = request -> CompletableFuture.completedFuture("PLAN");
    private final List<ControlEvent> events = new ArrayList<>();
    private AgentController controller;

    private BehaviorOption work() {
        return new BehaviorOption("work", "do the job", 1.0,
                List.of(Consideration.of("work", 1.0, s -> s.signal("work"))),
                () -> {
                    JsonObject questions = new JsonObject();
                    questions.add("route", DecisionQuestion.choice("how?", Map.of("fast", "do it", "safe", "be careful")));
                    DecisionNode route = new DecisionNode(decisions, questions, "route",
                            Map.of("fast", new ActionNode("fast", c -> Node.Status.SUCCESS)),
                            new ActionNode("safe", c -> Node.Status.SUCCESS));
                    return new BehaviorTree("work", new Sequence(new Condition("has work", c -> true), route));
                });
    }

    private BehaviorOption flee() {
        return new BehaviorOption("flee", "run away", 0.0,
                List.of(Consideration.of("threat", 10.0, s -> s.signal("threat"))),
                () -> new BehaviorTree("flee", new ActionNode("run", c -> {
                    int n = c.getInt("flee", 0) + 1;
                    c.put("flee", n);
                    return n >= 2 ? Node.Status.SUCCESS : Node.Status.RUNNING;
                })));
    }

    @BeforeEach
    void setUp() {
        decisions.enqueue(new DecisionResult(JsonParser.parseString(
                "{\"route\":{\"choice\":\"fast\",\"confidence\":0.9}}").getAsJsonObject(), "mock"));
        controller = new AgentController("test", decisions, planner,
                new UtilityArbiter(), List.of(work(), flee()));
        controller.subscribe(events::add);
    }

    private ControlState state(double work, double threat, long now) {
        return ControlState.of(now).with("work", work).with("threat", threat);
    }

    @Test
    void selectsRunsAndCompletesABehavior() {
        controller.tick(state(1.0, 0.0, 1));
        assertEquals("work", controller.activeId());

        // JEV 的答案在第一次 tick 就绪;第二次 tick 才由分支结算。
        controller.tick(state(1.0, 0.0, 2));

        assertNull(controller.activeId(), "行为成功后该空出来");
        assertTrue(events.stream().anyMatch(e -> e.kind().equals(ControlEvent.SELECTED) && e.text().equals("work")));
        assertTrue(events.stream().anyMatch(e -> e.kind().equals(ControlEvent.SUCCESS) && e.text().equals("work")));
        assertTrue(decisions.lastState().contains("work=1.0"));
    }

    @Test
    void aThreatPreemptsTheRunningBehavior() {
        controller.tick(state(1.0, 0.0, 1));
        assertEquals("work", controller.activeId());

        controller.tick(state(1.0, 1.0, 2));
        assertEquals("flee", controller.activeId());
        assertTrue(events.stream().anyMatch(e -> e.kind().equals(ControlEvent.PREEMPTED) && e.text().equals("work")));

        controller.tick(state(1.0, 1.0, 3));
        assertNull(controller.activeId());
        assertTrue(events.stream().anyMatch(e -> e.kind().equals(ControlEvent.SUCCESS) && e.text().equals("flee")));
    }

    @Test
    void externalEventsAreRelayedButDoNotPickTheBody() {
        controller.accept(ControlEvent.of("tool.finished", "mine"));
        assertEquals("tool.finished", events.get(0).kind());
        assertNull(controller.activeId(), "事件本身不选行为——选择仍只在仲裁器一处");
    }
}
