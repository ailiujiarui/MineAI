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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). This is the strip of
 * baritone.pathing.movement.Moves that AStarPathFinder reads: the fixed
 * offset, the dynamic flags, and apply(). The concrete Minecraft movements
 * are replaced by whatever the supplied CalculationContext offers.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * A movement the search may take from a node. Baritone's version is an enum
 * over specific Minecraft movements; here it is an interface so the search
 * core stays free of the world.
 *
 * @author leijurv
 */
public interface Move {

    /** @return the fixed x offset of this move, used for the loaded-chunk check. */
    int xOffset();

    /** @return the fixed z offset of this move, used for the loaded-chunk check. */
    int zOffset();

    /** @return the fixed y offset of this move, used for the world-height clamp. */
    int yOffset();

    /** @return whether the destination's x/z are decided by the world, not the offset. */
    boolean dynamicXZ();

    /** @return whether the destination's y is decided by the world, not the offset. */
    boolean dynamicY();

    /**
     * Applies this move from {@code (x, y, z)} and writes the destination and
     * cost into {@code result}. A cost at or above {@link ActionCosts#COST_INF}
     * means the move is impossible.
     */
    void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result);
}
