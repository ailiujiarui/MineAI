package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.decision.DecisionPort;
import com.dwinovo.numen.agent.decision.DecisionResult;
import com.dwinovo.numen.agent.decision.MockDecisionProvider;
import com.dwinovo.numen.agent.http.CancelToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevGoalJudgeTest {

    private static DecisionResult answers(String json) {
        return new DecisionResult(JsonParser.parseString(json).getAsJsonObject(), "jev");
    }

    private static AtomicReference<GoalJudge.Outcome> judge(MockDecisionProvider port, double minConfidence) {
        AtomicReference<GoalJudge.Outcome> out = new AtomicReference<>();
        new JevGoalJudge(port, minConfidence).judge(
                GoalState.of("mine 64 iron", 0L), "facts", "since", new CancelToken(), out::set);
        return out;
    }

    @Test
    void highConfidenceMetEndsTheGoal() {
        MockDecisionProvider port = new MockDecisionProvider().enqueue(answers(
                "{\"met\":{\"noul\":0.9,\"confidence\":0.9},\"stuck\":{\"noul\":0.1,\"confidence\":0.8}}"));
        GoalJudge.Outcome outcome = judge(port, 0.5).get();
        assertTrue(outcome.ok());
        assertTrue(outcome.verdict().met());
        assertFalse(outcome.verdict().stuck());
    }

    @Test
    void lowConfidenceMeansNotMetEvenIfStuckIsHigh() {
        MockDecisionProvider port = new MockDecisionProvider().enqueue(answers(
                "{\"met\":{\"noul\":0.4,\"confidence\":0.9},\"stuck\":{\"noul\":0.9,\"confidence\":0.9}}"));
        GoalJudge.Outcome outcome = judge(port, 0.5).get();
        assertFalse(outcome.verdict().met());
        assertTrue(outcome.verdict().stuck(), "没达成 + 没进展 = 打转");
    }

    @Test
    void aCancelledJudgementDropsTheAnswer() {
        MockDecisionProvider port = new MockDecisionProvider().enqueue(answers(
                "{\"met\":{\"noul\":0.9,\"confidence\":0.9}}"));
        CancelToken cancelled = new CancelToken();
        cancelled.cancel();
        AtomicReference<GoalJudge.Outcome> out = new AtomicReference<>();
        new JevGoalJudge(port, 0.5).judge(GoalState.of("x", 0L), "", "", cancelled, out::set);
        assertNull(out.get(), "判的已经不是眼前的局面了,答案作废");
    }

    @Test
    void aMissingAnswerIsTreatedAsNotMet() {
        MockDecisionProvider port = new MockDecisionProvider().onEmpty(DecisionResult.empty());
        GoalJudge.Outcome outcome = judge(port, 0.5).get();
        assertTrue(outcome.ok());
        assertFalse(outcome.verdict().met(), "读不到答案不能当达成");
    }

    @Test
    void anUnreachableJudgeIsAFailureNotASuccess() {
        DecisionPort broken = new DecisionPort() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public CompletableFuture<DecisionResult> decide(String state, JsonObject questions) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
        AtomicReference<GoalJudge.Outcome> out = new AtomicReference<>();
        new JevGoalJudge(broken, 0.5).judge(GoalState.of("x", 0L), "", "", new CancelToken(), out::set);
        assertFalse(out.get().ok(), "判断层不可用就是判不出来,不能当达成");
    }
}
