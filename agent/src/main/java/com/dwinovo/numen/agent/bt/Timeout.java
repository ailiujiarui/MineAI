package com.dwinovo.numen.agent.bt;

/**
 * 超时装饰:子节点跑超过 {@code ticks} 个 tick 还没完就判失败。
 *
 * <p>MineAI 的 {@code ActionQueue} 每条动作有 tick 上限,这里同一思想，但作用在子树。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Timeout implements Node {

    private final Node child;
    private final long ticks;
    private long start = -1;

    public Timeout(Node child, long ticks) {
        this.child = child;
        this.ticks = Math.max(1, ticks);
    }

    @Override
    public Status tick(BtContext ctx) {
        if (start < 0) {
            start = ctx.tick();
        }
        if (ctx.tick() - start >= ticks) {
            reset();
            return Status.FAILURE;
        }
        Status status = child.tick(ctx);
        if (status != Status.RUNNING) {
            reset();
        }
        return status;
    }

    @Override
    public void reset() {
        start = -1;
        child.reset();
    }
}
