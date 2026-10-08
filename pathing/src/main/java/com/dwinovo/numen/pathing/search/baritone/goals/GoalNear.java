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
 * baritone.api.pathing.goals.GoalNear. The rendering interface, settings
 * censoring, and the no-argument heuristic() helper (not read by the search
 * core) are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

/**
 * Any node within a given straight-line distance of a block. Baritone uses
 * this to follow a moving target: getting close is enough, and the search keeps
 * re-planning.
 *
 * <p>Like upstream, the heuristic is the distance to the center, not to the
 * nearest boundary of the ball, so it is not a strict lower bound once the
 * range is a meaningful fraction of the remaining distance. It is kept
 * faithful on purpose; the goals that must stay admissible for optimal
 * routing are {@link GoalBlock} and {@link GoalXZ}.
 */
public final class GoalNear implements Goal {

    private final int x;
    private final int y;
    private final int z;
    private final int rangeSq;

    public GoalNear(BlockPos pos, int range) {
        this(pos.getX(), pos.getY(), pos.getZ(), range);
    }

    public GoalNear(int x, int y, int z, int range) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.rangeSq = range * range;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= rangeSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return GoalBlock.calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalNear other && x == other.x && y == other.y && z == other.z && rangeSq == other.rangeSq;
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) + rangeSq;
    }

    @Override
    public String toString() {
        return "GoalNear{x=" + x + ", y=" + y + ", z=" + z + ", rangeSq=" + rangeSq + "}";
    }
}
