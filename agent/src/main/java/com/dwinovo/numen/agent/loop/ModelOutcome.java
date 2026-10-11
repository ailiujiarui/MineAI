package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;

/** 一次模型调用的结果。失败不抛异常,写成带原因的值交回来,要不要重试由内核决定。 */
public sealed interface ModelOutcome {

    /** 模型回话了,附上服务端报的用量。 */
    record Answered(AssistantTurn turn, Usage usage) implements ModelOutcome {}

    /**
     * 调用失败;{@code words} 是给主人看的分类原因,{@code retryable} 说这次失败重发一次有没有意义——
     * 网络、超时、限流、服务端错误是能重试的;参数错、密钥错、上下文超限这类确定性失败不算。
     */
    record Failed(String words, boolean retryable) implements ModelOutcome {}
}
