package com.dwinovo.numen.agent.control;

import java.util.concurrent.CompletableFuture;

/**
 * LLM 边界口:行为树里少数几个"没谱"的节点(拆计划、消歧义、兜不住的失败再规划)才调它。
 *
 * <p>把 LLM 收在一个口上,是为了让它<b>出不了内循环</b>:普通步骤由行为树的确定性节点跑,
 * 只有显式挂了 LLM 节点的位置才会花一次 token。返回的是一个计划/说明文本,由节点自己解析。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
@FunctionalInterface
public interface PlannerPort {

    CompletableFuture<String> plan(String request);

    /** 没有可用的 LLM 时用:立刻失败,行为树走兜底分支。 */
    static PlannerPort unavailable() {
        return request -> CompletableFuture.failedFuture(new IllegalStateException("planner unavailable"));
    }
}
