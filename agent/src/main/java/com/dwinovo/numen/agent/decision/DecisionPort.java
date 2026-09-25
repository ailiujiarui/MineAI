package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * 判断层接口——照搬 MineAI 的 {@code DecisionProvider}。
 *
 * <p>它是"系统一":给定一段状态和一组带类型的题,回带校准置信度的答案。它<b>只选、只判、
 * 只打分</b>,不生成文本、不派工具——生成是 LLM 那一侧的事。两者分开,才能把 LLM 赶出内循环,
 * 同时在需要判断时用一个便宜、可门槛化的调用替代。
 *
 * <p>纯 JVM,不碰 Minecraft。实现:{@link JevDecisionProvider}(真端点)、
 * {@link MockDecisionProvider}(离线/测试)。
 */
public interface DecisionPort {

    String name();

    /**
     * @param state     自由文本或 JSON 的现场
     * @param questions 题键 → {@link DecisionQuestion} 造的带类型题
     */
    CompletableFuture<DecisionResult> decide(String state, JsonObject questions);
}
