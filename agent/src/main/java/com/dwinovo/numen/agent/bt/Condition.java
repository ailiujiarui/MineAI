package com.dwinovo.numen.agent.bt;

import java.util.function.Predicate;

/**
 * 条件叶子:现场满足就成功,否则失败。用来当守卫——"够得着吗""背包满了吗"。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Condition implements Node {

    private final String description;
    private final Predicate<BtContext> test;

    public Condition(String description, Predicate<BtContext> test) {
        this.description = description;
        this.test = test;
    }

    @Override
    public Status tick(BtContext ctx) {
        return test.test(ctx) ? Status.SUCCESS : Status.FAILURE;
    }

    @Override
    public String describe() {
        return "condition(" + description + ")";
    }
}
