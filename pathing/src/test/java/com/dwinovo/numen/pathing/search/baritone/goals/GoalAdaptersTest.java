/*
 * Tests for the bridge from Numen's own goals to the vendored Baritone goals:
 * a position target keeps its exact shape, and any other target needs (and
 * uses) a stance lookup to answer the stance-free reach test.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalAdaptersTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void aCellPositionBecomesExactlyAGoalBlock() {
        Goal goal = GoalAdapters.adapt(Goals.at(new BlockPos(3, 64, 5)));

        assertInstanceOf(GoalBlock.class, goal);
        assertTrue(goal.isInGoal(3, 64, 5));
        assertFalse(goal.isInGoal(3, 64, 6));
    }

    @Test
    void aColumnPositionBecomesAGoalXZ() {
        Goal goal = GoalAdapters.adapt(Goals.column(2, 9));

        assertInstanceOf(GoalXZ.class, goal);
        assertTrue(goal.isInGoal(2, 100, 9));
        assertTrue(goal.isInGoal(2, -40, 9));
        assertFalse(goal.isInGoal(3, 100, 9));
    }

    @Test
    void aLevelPositionBecomesAGoalYLevel() {
        Goal goal = GoalAdapters.adapt(Goals.level(70));

        assertInstanceOf(GoalYLevel.class, goal);
        assertTrue(goal.isInGoal(5, 70, -200));
        assertFalse(goal.isInGoal(5, 71, -200));
    }

    @Test
    void aGoalThatNeedsABodyIsRefusedWithoutAStanceSource() {
        com.dwinovo.numen.pathing.search.Goal bodyDependent = new com.dwinovo.numen.pathing.search.Goal() {
            @Override
            public boolean contains(int x, int y, int z, Stance stance) {
                return false;
            }

            @Override
            public double estimate(int x, int y, int z) {
                return 0;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> GoalAdapters.adapt(bodyDependent));
    }

    @Test
    void aStanceSourceLetsAnyGoalBeAdapted() {
        com.dwinovo.numen.pathing.search.Goal target = new com.dwinovo.numen.pathing.search.Goal() {
            @Override
            public boolean contains(int x, int y, int z, Stance stance) {
                return stance != null && x == 7 && y == 8 && z == 9;
            }

            @Override
            public double estimate(int x, int y, int z) {
                return Math.abs(x - 7) + Math.abs(y - 8) + Math.abs(z - 9);
            }
        };
        StanceSource stances = (x, y, z) -> new Stance(Stance.Kind.GROUND, y, y - 1);

        Goal adapted = GoalAdapters.adapt(target, stances);

        assertTrue(adapted.isInGoal(7, 8, 9));
        assertFalse(adapted.isInGoal(0, 0, 0));
        assertEquals(1.0, adapted.heuristic(7, 9, 9), 1.0E-9);
    }
}
