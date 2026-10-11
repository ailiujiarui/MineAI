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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The minimal subset of
 * baritone.api.pathing.goals.Goal that the A* search core needs.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * Where a search is trying to go: which nodes count as arrived, and how far a
 * node still is from the target.
 *
 * @author leijurv
 */
public interface Goal {

    /**
     * @return whether the node {@code (x, y, z)} is inside this goal.
     */
    boolean isInGoal(int x, int y, int z);

    /**
     * @return an estimate (a lower bound) of the cost to reach this goal from
     *         the node {@code (x, y, z)}.
     */
    double heuristic(int x, int y, int z);
}
