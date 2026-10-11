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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0), from
 * baritone.api.pathing.goals.GoalGetToBlock. The rendering interface and
 * settings censoring are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

/**
 * Stand next to a block instead of inside it. Useful for chests and for
 * mining: the search stops on a node adjacent to the target so the hand can
 * reach it.
 *
 * <p>Faithful to Baritone, including the heuristic reaching the block itself;
 * because the stop is one node away, that can overestimate the remaining cost
 * by about one step. It is used where being adjacent, not the exact tick
 * count, is the point.
 *
 * @author avecowa
 */
public final class GoalGetToBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalGetToBlock(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalGetToBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return Math.abs(xDiff) + Math.abs(yDiff < 0 ? yDiff + 1 : yDiff) + Math.abs(zDiff) <= 1;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return GoalBlock.calculate(xDiff, yDiff < 0 ? yDiff + 1 : yDiff, zDiff);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalGetToBlock other && x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) * -49639096;
    }

    @Override
    public String toString() {
        return "GoalGetToBlock{x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
