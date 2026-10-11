package com.dwinovo.numen.agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 印章本身:能写能读,能认出"那格已经不是那个方块了"。 */
class WorldAnchorTest {

    @Test
    void encodeAndDecodeRoundTrip() {
        WorldAnchor a = new WorldAnchor("minecraft:overworld", -340, 68, 120, "minecraft:chest");
        assertEquals(a, WorldAnchor.decode(a.encode()));
    }

    @Test
    void holdsOnlyWhenTheBlockIsStillThere() {
        WorldAnchor a = new WorldAnchor("minecraft:overworld", 1, 2, 3, "minecraft:chest");
        assertTrue(a.holds("minecraft:chest"));
        assertFalse(a.holds("minecraft:air"));
        assertFalse(a.holds(null), "读不出来不算对得上");
    }

    @Test
    void malformedStampsDecodeToNull() {
        assertNull(WorldAnchor.decode(null));
        assertNull(WorldAnchor.decode(""));
        assertNull(WorldAnchor.decode("minecraft:overworld 1 2 3"), "缺方块");
        assertNull(WorldAnchor.decode("minecraft:overworld a b c minecraft:chest"), "坐标不是数");
    }
}
