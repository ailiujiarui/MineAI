package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.decision.DecisionPort;
import com.dwinovo.numen.agent.decision.DecisionQuestion;
import com.dwinovo.numen.agent.http.CancelToken;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * JEV 判官:把"目标达成了吗""是不是原地打转"当成两道是/非题问判断层。
 *
 * <p>比 {@link LlmGoalJudge} 便宜、带校准置信度、可门槛。低于 {@code minConfidence} 一律
 * 当<b>没达成</b>——多跑一轮只是费点 token,提前收工是把没做完的当成做完了,两种错代价不对等。
 *
 * <p>判词是数值化的({@code met=0.83 stuck=0.1});JEV 的题型里没有自由文本理由,想要人读的
 * 解释就用默认的 LLM 判官。
 *
 * <p>注意:{@code onDone} 可能在 JEV 的完成线程上回调;宿主负责切回内核线程。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class JevGoalJudge implements GoalJudge {

    private final DecisionPort port;
    private final double minConfidence;

    public JevGoalJudge(DecisionPort port, double minConfidence) {
        this.port = port;
        this.minConfidence = Math.max(0.0D, Math.min(1.0D, minConfidence));
    }

    @Override
    public void judge(GoalState goal, String facts, String since, CancelToken cancel, Consumer<Outcome> onDone) {
        JsonObject questions = new JsonObject();
        questions.add("met", DecisionQuestion.noul(
                "Is this goal condition met, judged only from the supplied physical facts and recent history? "
                        + "Condition: " + goal.objective()));
        questions.add("stuck", DecisionQuestion.noul(
                "Has there been no meaningful progress since the last check on this condition? "
                        + "Condition: " + goal.objective()));
        String state = "facts:\n" + (facts == null ? "" : facts)
                + "\nrecent:\n" + (since == null ? "" : since);

        port.decide(state, questions).whenComplete((result, error) -> {
            if (cancel != null && cancel.isCancelled()) {
                return;   // 判的已经不是眼前的局面了
            }
            if (error != null || result == null) {
                onDone.accept(Outcome.failed(error == null ? "JEV 没回话" : String.valueOf(error.getMessage())));
                return;
            }
            double met = result.noul("met");
            double stuck = result.noul("stuck");
            boolean isMet = met >= minConfidence;
            boolean isStuck = !isMet && stuck >= minConfidence;
            String reason = "jev met=" + fmt(met) + " stuck=" + fmt(stuck)
                    + " conf=" + fmt(result.confidence("met"));
            onDone.accept(Outcome.of(new GoalPrompts.Verdict(isMet, isStuck, reason), 0L));
        });
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
