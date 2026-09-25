package com.dwinovo.numen.agent.bt;

import java.util.List;

/**
 * 顺序:从左到右依次跑,前一个成功才跑下一个;任一失败即失败。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Sequence implements Node {

    private final List<Node> children;
    private int index;

    public Sequence(Node... children) {
        this(List.of(children));
    }

    public Sequence(List<Node> children) {
        this.children = List.copyOf(children);
    }

    @Override
    public Status tick(BtContext ctx) {
        while (index < children.size()) {
            Status status = children.get(index).tick(ctx);
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            if (status == Status.FAILURE) {
                reset();
                return Status.FAILURE;
            }
            index++;
        }
        reset();
        return Status.SUCCESS;
    }

    @Override
    public void reset() {
        index = 0;
        children.forEach(Node::reset);
    }
}
