/*
 * Tests for the ported Baritone movement cost calculators. They run on a small
 * in-memory world (Numen's TestWorld) with real vanilla block states, and pin
 * down the walk formula and the downward reachability rule.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.search.baritone.movement.movements.MovementDownward;
import com.dwinovo.numen.pathing.search.baritone.movement.movements.MovementTraverse;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementCostTest {

    @BeforeAll
    static void bootRegistry() {
        Vanilla.boot();
    }

    private static CalculationContext context(TestWorld world) {
        return new CalculationContext(world, world.border());
    }

    @Test
    void walkingOneBlockOverStoneCostsOneSprintStep() {
        TestWorld world = new TestWorld();
        world.set(0, 63, 0, Blocks.STONE.defaultBlockState());
        world.set(1, 63, 0, Blocks.STONE.defaultBlockState());

        double cost = MovementTraverse.cost(context(world), 0, 64, 0, 1, 0);

        // Nothing to break, not water, not sneaking, so the walk is sprinted.
        assertEquals(ActionCosts.SPRINT_ONE_BLOCK_COST, cost, 1e-9);
    }

    @Test
    void steppingDownWithNoFloorTwoBelowIsImpossible() {
        TestWorld world = new TestWorld();
        world.set(0, 63, 0, Blocks.STONE.defaultBlockState());

        double cost = MovementDownward.cost(context(world), 0, 64, 0);

        assertEquals(ActionCosts.COST_INF, cost, 0);
    }

    @Test
    void steppingDownThroughAirOntoALedgeCostsExactlyOneBlockOfFalling() {
        TestWorld world = new TestWorld();
        world.set(0, 62, 0, Blocks.STONE.defaultBlockState());

        double cost = MovementDownward.cost(context(world), 0, 64, 0);

        assertEquals(ActionCosts.FALL_N_BLOCKS_COST[1], cost, 1e-9);
    }
}
