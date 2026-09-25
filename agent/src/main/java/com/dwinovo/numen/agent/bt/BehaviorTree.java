package com.dwinovo.numen.agent.bt;

/**
 * 一棵行为树:名字 + 根节点。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class BehaviorTree {

    private final String name;
    private final Node root;

    public BehaviorTree(String name, Node root) {
        this.name = name;
        this.root = root;
    }

    public String name() {
        return name;
    }

    public Node.Status tick(BtContext ctx) {
        return root.tick(ctx);
    }

    public void reset() {
        root.reset();
    }
}
