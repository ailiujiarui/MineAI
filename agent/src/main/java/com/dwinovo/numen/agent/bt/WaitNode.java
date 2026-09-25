package com.dwinovo.numen.agent.bt;

/**
 * 等待叶子:等 {@code ticks} 个 tick 再成功。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class WaitNode implements Node {

    private final long ticks;
    private long remaining = -1;

    public WaitNode(long ticks) {
        this.ticks = Math.max(1, ticks);
    }

    @Override
    public Status tick(BtContext ctx) {
        if (remaining < 0) {
            remaining = ticks;
        }
        if (remaining <= 0) {
            remaining = -1;
            return Status.SUCCESS;
        }
        remaining--;
        return Status.RUNNING;
    }

    @Override
    public void reset() {
        remaining = -1;
    }
}
