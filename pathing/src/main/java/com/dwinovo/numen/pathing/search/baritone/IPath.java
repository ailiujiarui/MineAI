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
 * baritone.api.pathing.calc.IPath the search core and its callers use.
 */
package com.dwinovo.numen.pathing.search.baritone;

import java.util.List;

/**
 * A computed path: the ordered nodes it walks, plus its endpoints.
 *
 * @author leijurv
 */
public interface IPath {

    /** @return the nodes of this path, first is the start and last is the destination. */
    List<BetterBlockPos> positions();

    /** @return the position the path starts at. */
    BetterBlockPos getSrc();

    /** @return the position the path ends at. */
    BetterBlockPos getDest();

    /** @return the goal this path was calculated for. */
    Goal getGoal();

    /** @return the number of positions in this path. */
    int length();

    /** @return the number of nodes the search considered while calculating this path. */
    int getNumNodesConsidered();

    /**
     * Finalizes this path once the search is done. The Minecraft-specific
     * post-processing (assembling movements, cutting off unloaded chunks) is
     * intentionally not part of this port; this returns the path unchanged.
     */
    IPath postProcess();
}
