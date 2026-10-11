/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The handful of movement
 * cost constants the goal heuristics read out of Baritone's ActionCosts and
 * its costHeuristic setting. Kept local to this package so the ported goal
 * family stays independent of Numen's own cost model; the values match both
 * Baritone's defaults and com.dwinovo.numen.pathing.plan.ActionCosts'
 * estimate weights.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

/**
 * The per-block costs the reconstructed goal heuristics are built from.
 *
 * <p>Baritone scales the horizontal octile distance by its {@code costHeuristic}
 * setting (default {@code 3.563}, exactly the sprinting time for one block) and
 * scales vertical distance by the simulated fall and jump times. The numbers
 * here are the same, so a goal's heuristic is a lower bound on the true cost on
 * a grid whose moves cost at least these amounts.
 */
final class HeuristicCosts {

    private HeuristicCosts() {}

    /** Vanilla gravity per tick and the vertical air drag (all costs are in ticks). */
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /** Horizontal cost of one block: sprinting, Baritone's {@code costHeuristic} default. */
    static final double COST_PER_BLOCK = 20 / 5.612;

    /** Ticks to fall {@code distance} blocks from rest, vanilla gravity and drag. */
    static double fall(double distance) {
        if (distance <= 0) {
            return 0;
        }
        double left = distance;
        double velocity = 0;
        int ticks = 0;
        while (velocity <= 0 || left > velocity) {
            left -= velocity;
            ticks++;
            velocity = (velocity + GRAVITY) * DRAG;
        }
        return ticks + left / velocity;
    }

    /** Ticks to jump up one block: the ascent half of the 1.25-block parabola. */
    static final double JUMP_ONE_BLOCK = fall(1.25) - fall(0.25);

    /**
     * Ticks charged for descending one block. Baritone's GoalYLevel halves the
     * cost of a two-block fall; it must stay positive so a goal directly above a
     * node does not estimate zero and collapse partial paths back to the start.
     */
    static final double DESCEND_ONE_BLOCK = fall(2) / 2;
}
