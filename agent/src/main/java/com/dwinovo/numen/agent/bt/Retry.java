package com.dwinovo.numen.agent.bt;

/**
 * 重试装饰:子节点失败就重来,最多 {@code maxAttempts} 次;次数用完才失败。
 *
 * <p>"失败是局部的、有界的"——一个节点卡住不该把整条长链拖垮。重试期间回 {@link Status#RUNNING},
 * 于是每个 tick 都继续推。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Retry implements Node {

    private final Node child;
    private final int maxAttempts;
    private int attempts;

    public Retry(Node child, int maxAttempts) {
        this.child = child;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public Status tick(BtContext ctx) {
        if (attempts >= maxAttempts) {
            reset();
            return Status.FAILURE;
        }
        Status status = child.tick(ctx);
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        if (status == Status.SUCCESS) {
            reset();
            return Status.SUCCESS;
        }
        attempts++;
        child.reset();
        if (attempts >= maxAttempts) {
            reset();
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    @Override
    public void reset() {
        attempts = 0;
        child.reset();
    }
}
