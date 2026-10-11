package com.dwinovo.numen.agent.resume;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复连差异:拿一枚小指纹对着一个假的世界读者重读一遍,只报真变了的格,读不出来的跳过,
 * 物品按总数进出,超出上限截断。世界是源头,这里现算,不存第二份。
 */
class ResumeDiffTest {

    private static WorldSample sample(Map<String, String> blocks, Map<String, Integer> items,
                                      int x, int y, int z) {
        return new WorldSample("minecraft:overworld", x, y, z, blocks, items);
    }

    /** 一个从 (x,y,z) → 方块 id 的假当下世界;键不在 = 没加载。 */
    private static ResumeDiff.Reader world(Map<String, String> now) {
        return (dimension, x, y, z) -> now.get(x + "," + y + "," + z);
    }

    @Test
    void reportsOnlyBlocksThatActuallyChanged() {
        Map<String, String> before = Map.of(
                "10,64,10", "minecraft:chest",
                "11,64,10", "minecraft:oak_log",
                "12,64,10", "minecraft:stone");
        Map<String, String> now = Map.of(
                "10,64,10", "minecraft:chest",      // 没变
                "11,64,10", "minecraft:air",        // 砍了
                "12,64,10", "minecraft:gold_ore");  // 换了

        ResumeDiff.Result r = ResumeDiff.compute(sample(before, Map.of(), 10, 64, 10), Map.of(),
                10, 64, 10, world(now));

        assertEquals(0, r.moved());
        assertEquals(2, r.blocks().size());
        assertTrue(r.blocks().stream().anyMatch(c -> c.before().equals("minecraft:oak_log")
                && c.after().equals("minecraft:air")));
        assertTrue(r.blocks().stream().anyMatch(c -> c.after().equals("minecraft:gold_ore")));
        assertFalse(r.truncated());
    }

    @Test
    void anUnloadedCellIsSkippedNotCalledChanged() {
        Map<String, String> before = Map.of("10,64,10", "minecraft:chest");
        ResumeDiff.Result r = ResumeDiff.compute(sample(before, Map.of(), 10, 64, 10), Map.of(),
                10, 64, 10, world(Map.of()));   // 现在读不到
        assertTrue(r.empty(), "读不出来是没法核,不算变了");
        assertEquals("", ResumeDiff.format(r));
    }

    @Test
    void inventoryIsDiffedByTotals() {
        Map<String, Integer> before = Map.of("minecraft:iron_ore", 3, "minecraft:torch", 10);
        Map<String, Integer> now = Map.of("minecraft:iron_ore", 7, "minecraft:torch", 4,
                "minecraft:bread", 2);

        ResumeDiff.Result r = ResumeDiff.compute(sample(Map.of(), before, 0, 64, 0), now, 0, 64, 0,
                world(Map.of()));

        assertEquals(4, r.gained().get("minecraft:iron_ore"));
        assertEquals(2, r.gained().get("minecraft:bread"));
        assertEquals(6, r.lost().get("minecraft:torch"));
    }

    @Test
    void movementFromTheLeavePointIsReported() {
        ResumeDiff.Result r = ResumeDiff.compute(sample(Map.of(), Map.of(), 0, 64, 0), Map.of(),
                3, 64, 4, world(Map.of()));
        assertEquals(5, r.moved(), "3-4-5 直角三角形");
    }

    @Test
    void theChangeListIsBounded() {
        Map<String, String> before = new HashMap<>();
        Map<String, String> now = new HashMap<>();
        for (int i = 0; i < ResumeDiff.MAX_CHANGES + 10; i++) {
            before.put(i + ",64,0", "minecraft:stone");
            now.put(i + ",64,0", "minecraft:air");
        }
        ResumeDiff.Result r = ResumeDiff.compute(sample(before, Map.of(), 0, 64, 0), Map.of(),
                0, 64, 0, world(now));

        assertEquals(ResumeDiff.MAX_CHANGES, r.blocks().size());
        assertTrue(r.truncated(), "超出上限要如实标注");
        assertTrue(ResumeDiff.format(r).endsWith("go look.)"), ResumeDiff.format(r));
    }
}
