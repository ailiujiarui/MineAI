package com.dwinovo.numen.agent.bt;

import java.util.function.Function;

/**
 * 动作叶子:跑一个原子动作,回三态。宿主用世界操作实现它(挖、放、走、合成)。
 *
 * <p>约定:动作只汇报结果,<b>由树决定完成</b>。"走到掉落物旁边"回 SUCCESS 不等于"拿到材料"
 * ——那是流程(上层 Sequence/后置校验)的事。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class ActionNode implements Node {

    private final String description;
    private final Function<BtContext, Status> body;

    public ActionNode(String description, Function<BtContext, Status> body) {
        this.description = description;
        this.body = body;
    }

    @Override
    public Status tick(BtContext ctx) {
        Status status = body.apply(ctx);
        return status == null ? Status.FAILURE : status;
    }

    @Override
    public String describe() {
        return "action(" + description + ")";
    }
}
