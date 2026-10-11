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
 * baritone.api.pathing.goals.GoalYLevel. The rendering interface and settings
 * censoring are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.Goal;

/**
 * A target Y level, ignoring x and z. Useful for mining down to a layer.
 *
 * @author leijurv
 */
public final class GoalYLevel implements Goal {

    /** The target Y level. */
    public final int level;

    public GoalYLevel(int level) {
        this.level = level;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(level, y);
    }

    /** The vertical lower bound from {@code currentY} to {@code goalY}. */
    public static double calculate(int goalY, int currentY) {
        if (currentY > goalY) {
            return HeuristicCosts.DESCEND_ONE_BLOCK * (currentY - goalY);
        }
        if (currentY < goalY) {
            return (goalY - currentY) * HeuristicCosts.JUMP_ONE_BLOCK;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalYLevel other && level == other.level;
    }

    @Override
    public int hashCode() {
        return level * 1271009915;
    }

    @Override
    public String toString() {
        return "GoalYLevel{y=" + level + "}";
    }
}
