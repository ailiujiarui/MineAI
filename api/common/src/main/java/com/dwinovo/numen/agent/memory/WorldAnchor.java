package com.dwinovo.numen.agent.memory;

/**
 * 一条记在"某个地方/某个方块"上的札记的<b>有效印章</b>:它指向哪一格,写它的时候那一格是什么方块。
 *
 * <h2>为什么只是一枚印章,不是第二份世界模型</h2>
 * 世界永远是事实的源头。这枚印章不描述世界,只记下"我当时看到的是这个"——读的时候拿它和当下对一眼,
 * 不一样就把那条札记当作过期(世界已经变了,别再照它走),一样就放行。于是既不重复存世界,也没有
 * "两份状态各自漂移"的问题:漂移本身就是"过期"这个结论。
 *
 * <h2>读不出来不算过期</h2>
 * 方块读不到(区块没加载、她在别的维度)时返回 {@code null},这代表"没法核",不是"核不过"——放行,
 * 让原来那条通道(她走一趟自己看回执)去兜底。只有读到<b>另一个具体方块</b>才算过期。
 *
 * <p>纯 JVM,不碰 Minecraft:真正的方块由客户端经 {@link NoteBook.BlockLookup} 注入。
 */
public record WorldAnchor(String dimension, int x, int y, int z, String block) {

    public WorldAnchor {
        if (block == null || block.isBlank()) {
            throw new IllegalArgumentException("anchor needs the block that was there");
        }
    }

    /** 一行 frontmatter:`dimension x y z block`。 */
    public String encode() {
        return dimension + " " + x + " " + y + " " + z + " " + block;
    }

    /** {@link #encode()} 的反方向;读不成(没有、写坏)返回 {@code null},那条札记照旧但不核。 */
    public static WorldAnchor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.strip().split("\\s+");
        if (parts.length != 5) {
            return null;
        }
        try {
            return new WorldAnchor(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), parts[4]);
        } catch (NumberFormatException notACell) {
            return null;
        }
    }

    /** 现在那一格还是不是这个方块。 */
    public boolean holds(String actual) {
        return actual != null && actual.equals(block);
    }
}
