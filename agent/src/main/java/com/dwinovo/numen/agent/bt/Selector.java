package com.dwinovo.numen.agent.bt;

import java.util.List;

/**
 * 选择:从左到右找第一个成功的;全失败才失败。前面失败就试下一个——这是回退分支的落点。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Selector implements Node {

    private final List<Node> children;
    private int index;

    public Selector(Node... children) {
        this(List.of(children));
    }

    public Selector(List<Node> children) {
        this.children = List.copyOf(children);
    }

    @Override
    public Status tick(BtContext ctx) {
        while (index < children.size()) {
            Status status = children.get(index).tick(ctx);
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            if (status == Status.SUCCESS) {
                reset();
                return Status.SUCCESS;
            }
            index++;
        }
        reset();
        return Status.FAILURE;
    }

    @Override
    public void reset() {
        index = 0;
        children.forEach(Node::reset);
    }
}
