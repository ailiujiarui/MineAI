package com.dwinovo.numen.agent.acceptance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 判据本身的行为——照 MineAI 的 {@code ObjectiveTest} 逐条搬,保证语义没走样。 */
class ObjectiveTest {

    @Test
    void absoluteHoldingIgnoresBaseline() {
        Objective objective = Objective.haveItem("minecraft:iron_ore", 3);
        assertTrue(objective.met(3, 0));
        assertTrue(objective.met(5, 100));
        assertFalse(objective.met(2, 0));
    }

    @Test
    void relativeGainIsMeasuredFromTaskStart() {
        Objective objective = Objective.gainItem("minecraft:iron_ore", 3);
        assertTrue(objective.met(4, 1), "started with 1, now 4: gained exactly 3");
        assertFalse(objective.met(12, 10), "started with 10, now 12: only gained 2");
    }

    @Test
    void deliveryIsRelativeToTheRequesterStart() {
        Objective objective = Objective.deliverItem("minecraft:iron_pickaxe", 1);
        assertFalse(objective.met(1, 1), "already held one is not a delivery");
        assertTrue(objective.met(2, 1));
    }

    @Test
    void jsonRoundTripKeepsType() {
        Objective original = Objective.gainItem("minecraft:oak_log", 4);
        Objective parsed = Objective.fromJson(original.toJson());
        assertTrue(parsed.isRelative());
        assertTrue(parsed.met(6, 2));
        assertFalse(parsed.met(5, 2));
    }
}
