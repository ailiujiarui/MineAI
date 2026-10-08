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
 * baritone.api.pathing.goals.GoalXZ. The settings lookup is replaced by the
 * local cost constant and the direction helper (Minecraft Vec3/Mth) is gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.Goal;

/**
 * A target column, any Y. Useful for long-range goals without a specific
 * height.
 *
 * @author leijurv
 */
public final class GoalXZ implements Goal {

    private static final double SQRT_2 = Math.sqrt(2);

    private final int x;
    private final int z;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, z - this.z);
    }

    /**
     * The horizontal lower bound from an offset: how long walking straight and
     * diagonally takes, which is the octile distance scaled by the per-block
     * cost. Never more than walking every offset one block at a time.
     */
    public static double calculate(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        return (diagonal * SQRT_2 + straight) * HeuristicCosts.COST_PER_BLOCK;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalXZ other && x == other.x && z == other.z;
    }

    @Override
    public int hashCode() {
        int hash = 1791873246;
        hash = hash * 222601791 + x;
        hash = hash * -1331679453 + z;
        return hash;
    }

    @Override
    public String toString() {
        return "GoalXZ{x=" + x + ",z=" + z + "}";
    }
}
