package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelRequest;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把现有大脑的旁路调用({@link AgentLoop#consult})接成 {@link PlannerPort}——让行为树里的
 * {@link LlmNode} 用上真的模型,而不是干等。
 *
 * <p>{@code consult} 本来就是"不占内核、不进历史、用量照样进账"的侧调用,正好当边界 LLM:
 * 行为树跑到显式挂了 LLM 节点的位置才会花这一次 token。
 *
 * <p>注意:{@code consult} 的回调在内核线程;{@link CompletableFuture} 完成线程即调用线程,
 * 行为树节点只读它,不再往回推进内核。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class LoopPlannerPort implements PlannerPort {

    private final AgentLoop loop;
    private final String systemPrompt;

    public LoopPlannerPort(AgentLoop loop, String systemPrompt) {
        this.loop = loop;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    @Override
    public CompletableFuture<String> plan(String request) {
        CompletableFuture<String> future = new CompletableFuture<>();
        ModelRequest modelRequest = new ModelRequest(
                List.of(new ConvoState.Msg.User(request == null ? "" : request)), List.of(), systemPrompt);
        loop.consult(LoopEvent.Purpose.GOAL, modelRequest, new CancelToken(), outcome -> {
            switch (outcome) {
                case ModelOutcome.Answered answered -> future.complete(answered.turn().content());
                case ModelOutcome.Failed failed ->
                        future.completeExceptionally(new IllegalStateException(failed.words()));
            }
        });
        return future;
    }
}
