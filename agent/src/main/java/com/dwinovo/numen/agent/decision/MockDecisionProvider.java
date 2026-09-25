package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * 离线/测试用判断层:预设答案按顺序发,记下最后一次的状态与题。
 *
 * <p>它存在是为了让判断层可测、可离线——验收靶场必须确定,不能把成败押在外部模型上。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class MockDecisionProvider implements DecisionPort {

    private final Deque<DecisionResult> scripted = new ArrayDeque<>();
    private DecisionResult fallback = DecisionResult.empty();
    private String lastState;
    private JsonObject lastQuestions;

    /** 排一条答案。 */
    public MockDecisionProvider enqueue(DecisionResult result) {
        scripted.addLast(result);
        return this;
    }

    /** 排完队之后的默认答案。 */
    public MockDecisionProvider onEmpty(DecisionResult result) {
        this.fallback = result;
        return this;
    }

    public String lastState() {
        return lastState;
    }

    public JsonObject lastQuestions() {
        return lastQuestions;
    }

    public int answered() {
        return scripted.isEmpty() ? -1 : scripted.size();
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public CompletableFuture<DecisionResult> decide(String state, JsonObject questions) {
        lastState = state;
        lastQuestions = questions;
        DecisionResult next = scripted.pollFirst();
        return CompletableFuture.completedFuture(next == null ? fallback : next);
    }
}
