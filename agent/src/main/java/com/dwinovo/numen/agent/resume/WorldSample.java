package com.dwinovo.numen.agent.resume;

import java.util.Map;

/**
 * 离开那一刻,她周围一小圈的<b>有界指纹</b>——不是一份世界模型,只是把"我当时看到的是这些"记下来,
 * 好让回来时能算出"什么变了"。方块只取一个稀疏格子上的几格(半径与步长由采样那一侧封顶),
 * 物品只取总数;从不存整片地形。
 *
 * <p>纯 JVM,可被 headless 单测钉住。
 *
 * @param dimension 她离开时所在的维度
 * @param x,y,z     她离开时站的格子(把指纹锚在她身上,回来时才知道她走了多远)
 * @param blocks    {@code "x,y,z"} → 方块 id,采样到的那些格
 * @param items     物品 id → 总数
 */
public record WorldSample(String dimension, int x, int y, int z,
                          Map<String, String> blocks, Map<String, Integer> items) {

    public WorldSample {
        blocks = Map.copyOf(blocks);
        items = Map.copyOf(items);
    }

    /** {@code "x,y,z"} → 三个整数;写坏返回 {@code null}。 */
    public static int[] cell(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException notACell) {
            return null;
        }
    }

    /** 一圈都没采到(区块没加载)。 */
    public boolean empty() {
        return blocks.isEmpty() && items.isEmpty();
    }
}
