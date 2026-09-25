package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.decision.DecisionResult;
import com.dwinovo.numen.agent.decision.MockDecisionProvider;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPredicateTest {

    private static DecisionResult answers(String json) {
        return new DecisionResult(JsonParser.parseString(json).getAsJsonObject(), "jev");
    }

    private static Observation empty() {
        return Observation.of(1L, Map.of());
    }

    @Test
    void highConfidenceIsMet() {
        MockDecisionProvider port = new MockDecisionProvider().enqueue(answers(
                "{\"met\":{\"noul\":0.8,\"confidence\":0.9}}"));
        DecisionPredicate predicate = new DecisionPredicate("the base looks safe", port, 0.5D, 1000L);
        AcceptanceVerdict verdict = predicate.evaluate(empty());
        assertTrue(verdict.met());
        assertTrue(verdict.reason().contains("noul=0.8"));
    }

    @Test
    void lowConfidenceIsNotMet() {
        MockDecisionProvider port = new MockDecisionProvider().enqueue(answers(
                "{\"met\":{\"noul\":0.2,\"confidence\":0.9}}"));
        DecisionPredicate predicate = new DecisionPredicate("the base looks safe", port, 0.5D, 1000L);
        assertFalse(predicate.evaluate(empty()).met());
    }

    @Test
    void noPortIsSafelyNotMet() {
        DecisionPredicate predicate = new DecisionPredicate("x", null, 0.5D, 1000L);
        AcceptanceVerdict verdict = predicate.evaluate(empty());
        assertFalse(verdict.met());
        assertTrue(verdict.reason().contains("没有判断层"));
    }

    @Test
    void jsonRoundTripKeepsTheKindAndIsSafeWithoutAPort() {
        DecisionPredicate original = new DecisionPredicate("x", null, 0.5D, 1000L);
        assertTrue(original.toJson().get("kind").getAsString().equals("decision"));
        GoalPredicate restored = GoalPredicate.fromJson(original.toJson());
        assertTrue(restored instanceof DecisionPredicate);
        assertFalse(restored.evaluate(empty()).met());
    }
}
