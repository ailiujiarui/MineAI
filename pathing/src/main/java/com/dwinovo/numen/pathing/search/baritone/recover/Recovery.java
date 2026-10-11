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
 * Added for Numen (LGPL-3.0). A small, standalone result type for the
 * stuck/backtrack recovery helper; it does not exist verbatim in Baritone but
 * names the two outcomes Baritone's PathingBehavior/PathExecutor produce.
 */
package com.dwinovo.numen.pathing.search.baritone.recover;

import java.util.Objects;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.PathCalculationResult;

/**
 * What the executor should do after a search ended without reaching the goal,
 * or after execution has become stuck.
 *
 * <p>This is the seam the vendored Baritone core does not expose on its own:
 * {@code AbstractNodeCostSearch} can answer {@code bestPathSoFar()} /
 * {@code pathToMostRecentNodeConsidered()}, and {@code PathingBehavior} knows
 * to start a fresh search when {@code PathExecutor} fails, but neither hands
 * the caller a single decision. {@link StuckRecovery} folds both into this
 * type.
 *
 * @author Numen
 */
public sealed interface Recovery permits Recovery.Fallback, Recovery.Recompute, Recovery.None {

    /**
     * Walk the best partial/backtrack path the search did find, rather than
     * giving up. Mirrors {@code AbstractNodeCostSearch#bestSoFar} (a partial
     * segment, reported as {@link PathCalculationResult.Type#SUCCESS_SEGMENT})
     * and, when that is empty, {@code pathToMostRecentNodeConsidered()}.
     *
     * @param result the fallback outcome; its path is non-null
     */
    record Fallback(PathCalculationResult result) implements Recovery {
        public Fallback {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(result.path(), "result.path()");
        }
    }

    /**
     * Throw away the current route and search again from {@code from}. Mirrors
     * Baritone's recompute when {@code PathExecutor} cancels: {@code
     * PathingBehavior.tickPath} calls {@code findPathInNewThread(pathStart())},
     * i.e. replans from the node the body actually occupies (the frontier of
     * progress), favoring the abandoned route through {@code Favoring}/
     * {@code createPathfinder(previous)}.
     *
     * @param from the node the new search must start from (Baritone's
     *             {@code pathStart()}/{@code expectedSegmentStart})
     */
    record Recompute(BetterBlockPos from) implements Recovery {
        public Recompute {
            Objects.requireNonNull(from, "from");
        }
    }

    /** Neither a usable partial path nor a recompute was asked for. */
    record None() implements Recovery {
    }
}
