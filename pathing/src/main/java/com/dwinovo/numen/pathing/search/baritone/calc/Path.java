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
 * baritone.pathing.calc.Path. Movement assembly and chunk cut-off have been
 * dropped because this port has no Minecraft world; the node chain, endpoints
 * and the "fake start node" edge case are preserved.
 */
package com.dwinovo.numen.pathing.search.baritone.calc;

import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.CalculationContext;
import com.dwinovo.numen.pathing.search.baritone.Goal;
import com.dwinovo.numen.pathing.search.baritone.IPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node based implementation of IPath
 *
 * @author leijurv
 */
public class Path implements IPath {

    /**
     * The start position of this path
     */
    private final BetterBlockPos start;

    /**
     * The end position of this path
     */
    private final BetterBlockPos end;

    /**
     * The blocks on the path. Guaranteed that path.get(0) equals start and
     * path.get(path.size()-1) equals end
     */
    private final List<BetterBlockPos> path;

    private final Goal goal;

    private final int numNodes;

    private volatile boolean verified;

    Path(BetterBlockPos realStart, PathNode start, PathNode end, int numNodes, Goal goal, CalculationContext context) {
        this.end = new BetterBlockPos(end.x, end.y, end.z);
        this.numNodes = numNodes;
        this.goal = goal;

        PathNode current = end;
        List<BetterBlockPos> tempPath = new ArrayList<>();
        while (current != null) {
            tempPath.add(new BetterBlockPos(current.x, current.y, current.z));
            current = current.previous;
        }

        // If the position the player is at is different from the position we told A* to start from,
        // and A* gave us no movements, then add a fake node that will allow a movement to be created
        // that gets us to the single position in the path.
        // See PathingBehavior#createPathfinder and https://github.com/cabaletta/baritone/pull/4519
        var startNodePos = new BetterBlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            this.start = realStart;
            tempPath.add(realStart);
        } else {
            this.start = startNodePos;
        }

        // Nodes are traversed last to first so we need to reverse the list
        Collections.reverse(tempPath);
        this.path = tempPath;
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public IPath postProcess() {
        if (verified) {
            throw new IllegalStateException("Path must not be verified twice");
        }
        verified = true;
        return this;
    }

    @Override
    public List<BetterBlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }

    @Override
    public BetterBlockPos getSrc() {
        return start;
    }

    @Override
    public BetterBlockPos getDest() {
        return end;
    }

    @Override
    public int length() {
        return path.size();
    }
}
