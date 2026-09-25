package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;

/**
 * 判断层回的一组带类型答案——照搬 MineAI 的 {@code DecisionResult}。
 *
 * <p>三种题对应三种读法:{@link #choice} 读选项、{@link #noul} 读是/否概率、
 * {@link #score} 读加权分。每一项都带一个校准过的 {@link #confidence}——低于门槛就别信,
 * 交给兜底。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record DecisionResult(JsonObject answers, String model) {

    public static DecisionResult empty() {
        return new DecisionResult(new JsonObject(), "");
    }

    private JsonObject answer(String key) {
        if (answers == null || !answers.has(key) || !answers.get(key).isJsonObject()) {
            return null;
        }
        return answers.getAsJsonObject(key);
    }

    /** {@code choice} 题选的选项;没有回 {@code null}。 */
    public String choice(String key) {
        JsonObject answer = answer(key);
        return answer != null && answer.has("choice") ? answer.get("choice").getAsString() : null;
    }

    /** {@code noul} 题的是/否概率;没有回 {@code -1}。 */
    public double noul(String key) {
        JsonObject answer = answer(key);
        return answer != null && answer.has("noul") ? answer.get("noul").getAsDouble() : -1.0D;
    }

    /** {@code score} 题的加权分;没有回 {@code -1}。 */
    public double score(String key) {
        JsonObject answer = answer(key);
        return answer != null && answer.has("score") ? answer.get("score").getAsDouble() : -1.0D;
    }

    /** 校准置信度;没有回 {@code -1}。 */
    public double confidence(String key) {
        JsonObject answer = answer(key);
        return answer != null && answer.has("confidence") ? answer.get("confidence").getAsDouble() : -1.0D;
    }

    public boolean has(String key) {
        return answer(key) != null;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        o.add("answers", answers == null ? new JsonObject() : answers);
        return o;
    }
}
