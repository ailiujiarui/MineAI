package com.dwinovo.numen.agent.plan;

/**
 * 一份物品与数量。纯 JVM,不碰 Minecraft。
 */
public record ItemStack(String item, int count) {

    public ItemStack {
        if (count < 1) {
            count = 1;
        }
    }

    public static ItemStack of(String item, int count) {
        return new ItemStack(item, count);
    }
}
