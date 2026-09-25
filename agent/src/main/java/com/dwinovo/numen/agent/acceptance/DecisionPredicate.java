package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.decision.DecisionPort;
import com.dwinovo.numen.agent.decision.DecisionQuestion;
import com.dwinovo.numen.agent.decision.DecisionResult;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 判断层谓词:判据不由机器量测表达,而是问 JEV 一道是/非题。
 *
 * <p>给"没有可机器量测的判据"用——比如"基地看起来安全了吗""这段解释够清楚吗"这种。
 * 有机器量测的一律优先用 {@link ObjectivePredicate}/{@link ProbePredicate}:能读世界就别问模型。
 *
 * <p>低于 {@code minConfidence} 一律当<b>没达成</b>(和 {@code JevGoalJudge} 同一口径)。
 * 判断失败、超时、没判断层,也都当没达成——判不出来不等于做完了。
 *
 * <p>{@link #evaluate} 阻塞等 JEV(和 RCON 靶场一样是同步量测口)。纯 JVM,不碰 Minecraft。
 */
public final class DecisionPredicate implements GoalPredicate {

    private final String condition;
    private final DecisionPort port;
    private final double minConfidence;
    private final long timeoutMs;

    public DecisionPredicate(String condition, DecisionPort port, double minConfidence, long timeoutMs) {
        this.condition = condition;
        this.port = port;
        this.minConfidence = Math.max(0.0D, Math.min(1.0D, minConfidence));
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    @Override
    public String condition() {
        return condition;
    }

    /** 判断层不需要 RCON 探针。 */
    @Override
    public List<Probe> probes() {
        return List.of();
    }

    @Override
    public AcceptanceVerdict evaluate(Observation obs) {
        if (port == null) {
            return AcceptanceVerdict.unmet(condition + " —— 没有判断层可用", List.of());
        }
        JsonObject questions = new JsonObject();
        questions.add("met", DecisionQuestion.noul(
                "Is this condition met, judged only from the observed state? Condition: " + condition));
        String state = obs == null ? "" : obs.toJson().toString();
        try {
            DecisionResult result = port.decide(state, questions).get(timeoutMs, TimeUnit.MILLISECONDS);
            double noul = result.noul("met");
            boolean met = noul >= minConfidence;
            ProbeResult evidence = new ProbeResult("jev", "systemone",
                    "noul=" + noul + " conf=" + result.confidence("met"), met);
            return met
                    ? AcceptanceVerdict.met(condition + " (jev noul=" + noul + ")", List.of(evidence))
                    : AcceptanceVerdict.unmet(
                            condition + " (jev noul=" + noul + " < " + minConfidence + ")", List.of(evidence));
        } catch (Exception failure) {
            return AcceptanceVerdict.unmet(condition + " —— JEV 判断失败:" + failure.getMessage(), List.of());
        }
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "decision");
        o.addProperty("condition", condition);
        o.addProperty("minConfidence", minConfidence);
        o.addProperty("timeoutMs", timeoutMs);
        return o;
    }

    /** 从落盘恢复时没有判断层可用,构造出来的谓词一律判"没达成"(安全默认)。 */
    public static DecisionPredicate fromJson(JsonObject o) {
        String condition = o.has("condition") ? o.get("condition").getAsString() : "";
        double minConfidence = o.has("minConfidence") ? o.get("minConfidence").getAsDouble() : 0.5D;
        long timeout = o.has("timeoutMs") ? o.get("timeoutMs").getAsLong() : 5000L;
        return new DecisionPredicate(condition, null, minConfidence, timeout);
    }
}
