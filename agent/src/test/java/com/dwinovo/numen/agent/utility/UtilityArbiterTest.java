package com.dwinovo.numen.agent.utility;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UtilityArbiterTest {

    private static BehaviorOption option(String id, double base, double weight, String signal) {
        return new BehaviorOption(id, id, base,
                List.of(Consideration.of(signal, weight, s -> s.signal(signal))),
                () -> new BehaviorTree(id, new ActionNode(id, c -> Node.Status.SUCCESS)));
    }

    @Test
    void picksTheHighestScoringBehavior() {
        UtilityArbiter arbiter = new UtilityArbiter();
        ControlState state = ControlState.of(0L).with("work", 1.0).with("threat", 0.2);
        List<BehaviorOption> options = List.of(
                option("rest", 0.1, 0.0, "rest"),
                option("work", 0.2, 1.0, "work"));
        assertEquals("work", arbiter.select(state, options).id());
    }

    @Test
    void hysteresisKeepsTheCurrentBehaviorWhenTheChallengerIsOnlySlightlyBetter() {
        UtilityArbiter arbiter = new UtilityArbiter(0.5);
        List<BehaviorOption> options = List.of(
                option("work", 1.0, 1.0, "work"),
                option("rest", 1.1, 1.0, "rest"));
        // work already holds the body (work=2.0 beats rest=1.1).
        assertEquals("work", arbiter.select(ControlState.of(0L).with("work", 1.0), options).id());
        // now rest is only +0.1 better (1.1 vs 1.0): within hysteresis, keep work.
        assertEquals("work", arbiter.select(ControlState.of(0L), options).id());
    }

    @Test
    void aClearlyBetterChallengerPreempts() {
        UtilityArbiter arbiter = new UtilityArbiter(0.5);
        List<BehaviorOption> options = List.of(
                option("work", 1.0, 1.0, "work"),
                option("flee", 1.0, 10.0, "threat"));
        assertEquals("work", arbiter.select(ControlState.of(0L).with("work", 1.0), options).id());
        assertEquals("flee", arbiter.select(ControlState.of(0L).with("work", 1.0).with("threat", 1.0), options).id());
    }

    @Test
    void noOptionsMeansIdle() {
        UtilityArbiter arbiter = new UtilityArbiter();
        assertNull(arbiter.select(ControlState.of(0L), List.of()));
        assertNotNull(arbiter.select(ControlState.of(0L), List.of(option("a", 1, 0, "a"))));
    }
}
