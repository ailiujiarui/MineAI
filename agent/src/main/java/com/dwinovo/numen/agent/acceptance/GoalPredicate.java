package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 机器可判的目标条件——"不能自称完成"的判据。
 *
 * <p>它必须能被一次世界量测({@link Observation})判真伪,不接受任何模型的自述。两种实现:
 *
 * <ul>
 *   <li>{@link ObjectivePredicate}:MineAI 的主形状——类型化库存目标(绝对持有 / 相对增量),
 *       对着权威持有量判。判词怎么写,看这里。</li>
 *   <li>{@link ProbePredicate}:非库存条件(某格是某方块、某实体存在)用通用 RCON 探针判。</li>
 * </ul>
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public interface GoalPredicate {

    /** 人读的条件正文(进提示词与报告)。 */
    String condition();

    /** 判它需要跑哪些世界量测。 */
    List<Probe> probes();

    /** 对着一次快照判。证据逐条带着探针 id,报告照抄。 */
    AcceptanceVerdict evaluate(Observation obs);

    JsonObject toJson();

    // ---- 工厂 ----

    /** 通用探针谓词:全部命中。 */
    static GoalPredicate all(String condition, List<Probe> probes) {
        return ProbePredicate.all(condition, probes);
    }

    /** 通用探针谓词:命中任意一条。 */
    static GoalPredicate any(String condition, List<Probe> probes) {
        return ProbePredicate.any(condition, probes);
    }

    /** 类型化库存目标。基线由调用方在设定目标时量下。 */
    static GoalPredicate objective(Objective objective, String subject, int baseline) {
        return new ObjectivePredicate(objective, subject, baseline);
    }

    static GoalPredicate fromJson(JsonObject o) {
        if (o == null) {
            return ProbePredicate.all("", List.of());
        }
        String kind = o.has("kind") ? o.get("kind").getAsString() : "probes";
        return switch (kind) {
            case "item" -> ObjectivePredicate.fromJson(o);
            case "decision" -> DecisionPredicate.fromJson(o);
            default -> ProbePredicate.fromJson(o);
        };
    }
}
