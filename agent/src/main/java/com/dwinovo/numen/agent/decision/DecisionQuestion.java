package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * JEV "System One" 的三种题——照搬 MineAI 的请求形状。
 *
 * <ul>
 *   <li>{@link #choice}:从 criteria 里选一个;</li>
 *   <li>{@link #noul}:是/否,回校准概率;</li>
 *   <li>{@link #score}:给一组 criteria 打加权分。</li>
 * </ul>
 *
 * <p>题是<b>有类型</b>的:判断层只能从代码给的选项/准则里选,不许自由发挥。这既守住了
 * 边界,也让答案可解析。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class DecisionQuestion {

    private DecisionQuestion() {}

    /** 选择题:{@code choice} 是 criteria 的键之一。 */
    public static JsonObject choice(String instructions, Map<String, String> criteria) {
        JsonObject question = new JsonObject();
        question.addProperty("type", "choice");
        question.addProperty("instructions", instructions);
        JsonObject criteriaObject = new JsonObject();
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            criteriaObject.addProperty(entry.getKey(), entry.getValue());
        }
        question.add("criteria", criteriaObject);
        return question;
    }

    /** 是非题:回 {@code noul}(yes 的概率)。 */
    public static JsonObject noul(String instructions) {
        JsonObject question = new JsonObject();
        question.addProperty("type", "noul");
        question.addProperty("instructions", instructions);
        return question;
    }

    /** 打分题:给每条准则打加权分,合成一个 {@code score}。 */
    public static JsonObject score(String instructions, List<String> criteria) {
        JsonObject question = new JsonObject();
        question.addProperty("type", "score");
        question.addProperty("instructions", instructions);
        JsonArray array = new JsonArray();
        for (String criterion : criteria) {
            array.add(criterion);
        }
        question.add("criteria", array);
        return question;
    }
}
