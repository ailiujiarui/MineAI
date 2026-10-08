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
 * baritone.api.pathing.goals.GoalBlock. The rendering interface and settings
 * censoring are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

/**
 * A specific block position: the node must be exactly that cell.
 *
 * @author leijurv
 */
public final class GoalBlock implements Goal {

    /** The X block position of this goal. */
    public final int x;
    /** The Y block position of this goal. */
    public final int y;
    /** The Z block position of this goal. */
    public final int z;

    public GoalBlock(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, y - this.y, z - this.z);
    }

    /**
     * The lower bound from a signed offset: vertical plus horizontal, the same
     * split Baritone uses so that a node one above the goal does not estimate
     * zero.
     */
    public static double calculate(double xDiff, int yDiff, double zDiff) {
        return GoalYLevel.calculate(0, yDiff) + GoalXZ.calculate(xDiff, zDiff);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalBlock other && x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) * 905165533;
    }

    @Override
    public String toString() {
        return "GoalBlock{x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
