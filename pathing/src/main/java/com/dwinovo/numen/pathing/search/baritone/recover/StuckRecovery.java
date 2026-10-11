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
 * Added for Numen (LGPL-3.0). Standalone recovery helper over the vendored
 * Baritone core; it mirrors, without editing, the relevant Baritone behavior:
 *
 *   - AbstractNodeCostSearch#bestSoFar(boolean, int) and
 *     #pathToMostRecentNodeConsidered(): the partial path a search keeps when
 *     it cannot reach the goal;
 *   - AbstractNodeCostSearch.MIN_DIST_PATH: bestSoFar only reports a partial
 *     once it is at least this far from the start;
 *   - AbstractNodeCostSearch.MIN_IMPROVEMENT: the relaxation threshold under
 *     which a candidate is not worth recording as a new best-so-far;
 *   - PathingBehavior#tickPath / PathExecutor#onTick: on failure the executor
 *     is dropped and a new search is started from pathStart() (the current
 *     "frontier"), biasing toward the old route with Favoring.
 */
package com.dwinovo.numen.pathing.search.baritone.recover;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.IPath;
import com.dwinovo.numen.pathing.search.baritone.IPathFinder;
import com.dwinovo.numen.pathing.search.baritone.PathCalculationResult;

/**
 * Turns the two failure signals of path execution into one decision:
 *
 * <ul>
 *   <li><b>Backtrack / partial.</b> After a finished calculation, {@link
 *       #onResult(PathCalculationResult)} returns the best partial path the
 *       search kept. It first honors a result that already carries a path
 *       (Baritone's {@code SUCCESS_SEGMENT}, produced by {@code bestSoFar}),
 *       then, when the result is a bare failure, falls back to the finder's
 *       {@code bestPathSoFar()} and finally to {@code
 *       pathToMostRecentNodeConsidered()}.</li>
 *   <li><b>Stuck.</b> Repeated {@link #onStuck(boolean, BetterBlockPos)}
 *       signals produce a {@link Recovery.Recompute} carrying the node the body
 *       now occupies, so the live executor can replan from there (Baritone's
 *       {@code pathStart()} recompute).</li>
 * </ul>
 *
 * <p>Self-contained and free of Minecraft: it only reads the vendored
 * {@link IPathFinder}/{@link PathCalculationResult}. Not thread-safe; call it
 * from the tick thread.
 *
 * @author Numen
 */
public final class StuckRecovery {

    /**
     * Consecutive stuck signals before a recompute is requested. Matches the
     * live segment state machine's {@code STRIKES} and Baritone's habit of
     * tolerating a transient hitch before abandoning the route.
     */
    public static final int DEFAULT_STUCK_STRIKES = 3;

    /**
     * A partial path needs at least a start and one move to be worth walking.
     * {@code bestSoFar} already enforces a much larger minimum (Baritone's
     * {@code MIN_DIST_PATH = 5}); this also filters the one-position path
     * {@code pathToMostRecentNodeConsidered()} can return for the start node.
     */
    public static final int MIN_PARTIAL_POSITIONS = 2;

    private final IPathFinder finder;
    private final int stuckStrikes;
    private int strikes;

    /** A recovery helper with no finder: partials are only honored from the result. */
    public StuckRecovery() {
        this(null, DEFAULT_STUCK_STRIKES);
    }

    /**
     * @param finder the finished finder to salvage a partial from; may be null
     *               if the caller always passes a result carrying its own path
     */
    public StuckRecovery(IPathFinder finder) {
        this(finder, DEFAULT_STUCK_STRIKES);
    }

    /**
     * @param finder       the finished finder to salvage a partial from, or null
     * @param stuckStrikes consecutive stuck signals required to recompute (>= 1)
     */
    public StuckRecovery(IPathFinder finder, int stuckStrikes) {
        if (stuckStrikes < 1) {
            throw new IllegalArgumentException("stuckStrikes must be >= 1, was " + stuckStrikes);
        }
        this.finder = finder;
        this.stuckStrikes = stuckStrikes;
    }

    /**
     * Decide what to do with a finished calculation. A result carrying a path
     * (including Baritone's partial {@code SUCCESS_SEGMENT}) is returned as-is;
     * a bare failure is upgraded to the best partial still held by the finder.
     *
     * @param result the finished calculation; may be null
     * @return a {@link Recovery.Fallback} with a non-null path, or {@link
     *         Recovery.None}
     */
    public Recovery onResult(PathCalculationResult result) {
        if (result != null && result.path() != null) {
            return new Recovery.Fallback(result);
        }
        return partialFallback();
    }

    /**
     * Mirrors Baritone's partial bookkeeping: try {@code bestPathSoFar()} (the
     * far-from-start partial {@code bestSoFar} reports), then the last node the
     * search touched ({@code pathToMostRecentNodeConsidered()}).
     */
    private Recovery partialFallback() {
        if (finder == null) {
            return new Recovery.None();
        }
        IPath partial = finder.bestPathSoFar().orElse(null);
        if (partial == null) {
            partial = finder.pathToMostRecentNodeConsidered().orElse(null);
        }
        if (partial == null || partial.length() < MIN_PARTIAL_POSITIONS) {
            return new Recovery.None();
        }
        return new Recovery.Fallback(new PathCalculationResult(
                PathCalculationResult.Type.SUCCESS_SEGMENT, partial));
    }

    /**
     * Record whether execution is stuck this tick. A non-stuck tick clears the
     * count; after {@code stuckStrikes} consecutive stuck ticks, returns a
     * recompute request from {@code current} and resets the count.
     *
     * @param stuck   whether the executor considers this tick stuck
     * @param current the node the body now occupies (Baritone's {@code
     *                pathStart()} frontier); may be null, in which case no
     *                recompute can be requested
     * @return a {@link Recovery.Recompute} on the strike threshold, else {@link
     *         Recovery.None}
     */
    public Recovery onStuck(boolean stuck, BetterBlockPos current) {
        if (!stuck || current == null) {
            strikes = 0;
            return new Recovery.None();
        }
        strikes++;
        if (strikes >= stuckStrikes) {
            strikes = 0;
            return new Recovery.Recompute(current);
        }
        return new Recovery.None();
    }

    /** @return the number of consecutive stuck signals counted so far. */
    public int strikes() {
        return strikes;
    }
}
