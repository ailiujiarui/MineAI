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
 * baritone.api.pathing.goals.GoalRunAway. The no-argument heuristic() helper
 * and settings censoring are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import java.util.Arrays;
import java.util.Objects;

import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

/**
 * Retreat: satisfactory once no position to flee from is within the given
 * distance. Used for combat spacing.
 *
 * <p>The heuristic is negative and grows as the body gets closer, which is what
 * makes a maximizing search pull away rather than toward.
 *
 * @author leijurv
 */
public final class GoalRunAway implements Goal {

    private final BlockPos[] from;
    private final int distanceSq;
    private final Integer maintainY;

    public GoalRunAway(double distance, BlockPos... from) {
        this(distance, null, from);
    }

    public GoalRunAway(double distance, Integer maintainY, BlockPos... from) {
        if (from.length == 0) {
            throw new IllegalArgumentException("Positions to run away from must not be empty");
        }
        this.from = from.clone();
        this.distanceSq = (int) (distance * distance);
        this.maintainY = maintainY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (maintainY != null && maintainY != y) {
            return false;
        }
        for (BlockPos p : from) {
            int diffX = x - p.getX();
            int diffZ = z - p.getZ();
            int distSq = diffX * diffX + diffZ * diffZ;
            if (distSq < distanceSq) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (BlockPos p : from) {
            double h = GoalXZ.calculate(p.getX() - x, p.getZ() - z);
            if (h < min) {
                min = h;
            }
        }
        min = -min;
        if (maintainY != null) {
            min = min * 0.6 + GoalYLevel.calculate(maintainY, y) * 1.5;
        }
        return min;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalRunAway other && distanceSq == other.distanceSq
                && Arrays.equals(from, other.from) && Objects.equals(maintainY, other.maintainY);
    }

    @Override
    public int hashCode() {
        int hash = Arrays.hashCode(from);
        hash = hash * 1196803141 + distanceSq;
        hash = hash * -2053788840 + (maintainY == null ? 0 : maintainY);
        return hash;
    }

    @Override
    public String toString() {
        return maintainY != null
                ? "GoalRunAwayFromMaintainY y=" + maintainY + ", " + Arrays.asList(from)
                : "GoalRunAwayFrom" + Arrays.asList(from);
    }
}
