package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * LLM 边界叶:把一次模型调用放在树里一个显式的位置,不在内循环里。
 *
 * <p>典型用法:计划节点——叫模型把目标拆成步骤,解析成 SUCCESS/FAILURE 后,后续由行为树
 * 确定性接管。请求发出后回 RUNNING,答案回来再定。调用失败就回 FAILURE,由 {@code Selector}
 * 走兜底分支。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class LlmNode implements Node {

    private final PlannerPort planner;
    private final String request;
    private final BiFunction<String, BtContext, Status> parse;

    private CompletableFuture<String> pending;
    private Status result;
    private boolean failed;

    /**
     * @param request 发给模型的请求文本
     * @param parse   读模型的回复,回 SUCCESS/FAILURE(或 RUNNING,如果想让它接着等)
     */
    public LlmNode(PlannerPort planner, String request, BiFunction<String, BtContext, Status> parse) {
        this.planner = planner;
        this.request = request;
        this.parse = parse;
    }

    @Override
    public Status tick(BtContext ctx) {
        if (result == null && !failed) {
            if (pending == null) {
                pending = planner.plan(request);
                pending.whenComplete((text, error) -> {
                    if (error != null) {
                        failed = true;
                    } else {
                        result = parse.apply(text, ctx);
                    }
                });
            }
            return Status.RUNNING;
        }
        if (failed) {
            return Status.FAILURE;
        }
        return result == null ? Status.FAILURE : result;
    }

    @Override
    public void reset() {
        pending = null;
        result = null;
        failed = false;
    }

    @Override
    public String describe() {
        return "llm";
    }
}
