package com.dwinovo.numen.agent.plan;

import java.util.Map;

/**
 * 权威库存的只读视图:某个物品现在有多少。宿主给(服务端背包),编译器不猜。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
@FunctionalInterface
public interface Counts {

    int count(String item);

    default boolean atLeast(String item, int n) {
        return count(item) >= n;
    }

    static Counts empty() {
        return item -> 0;
    }

    static Counts of(Map<String, Integer> counts) {
        Map<String, Integer> copy = Map.copyOf(counts);
        return item -> copy.getOrDefault(item, 0);
    }
}
