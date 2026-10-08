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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The subset of
 * baritone.api.pathing.calc.IPathFinder that the A* core implements.
 */
package com.dwinovo.numen.pathing.search.baritone;

import java.util.Optional;

/**
 * A pathfinder that runs a calculation and can be cancelled or polled mid-run.
 *
 * @author leijurv
 */
public interface IPathFinder {

    /**
     * Calculates a path, blocking until it succeeds, fails, or times out.
     *
     * @param primaryTimeout if a path is found within this time, return it.
     * @param failureTimeout if no path is found within this time, give up.
     */
    PathCalculationResult calculate(long primaryTimeout, long failureTimeout);

    /** @return the path to the most recently considered node, if any. */
    Optional<IPath> pathToMostRecentNodeConsidered();

    /** @return the best path found so far, which may not reach the goal. */
    Optional<IPath> bestPathSoFar();

    /** @return whether this finder has finished and cannot be reused. */
    boolean isFinished();

    /** @return the goal this finder searches for. */
    Goal getGoal();

    /** Requests that the calculation stop as soon as possible. */
    void cancel();
}
