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
 * baritone.pathing.calc.AStarPathFinder. The A* loop is preserved faithfully:
 * the timeout checks, the cancel check, the move expansion, favoring, the
 * relaxation with MIN_IMPROVEMENT, and the bestSoFar bookkeeping. Moves and
 * world queries now come from the supplied CalculationContext, and the
 * settings/logging are gone.
 */
package com.dwinovo.numen.pathing.search.baritone.calc;

import com.dwinovo.numen.pathing.search.baritone.ActionCosts;
import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.CalculationContext;
import com.dwinovo.numen.pathing.search.baritone.Favoring;
import com.dwinovo.numen.pathing.search.baritone.Goal;
import com.dwinovo.numen.pathing.search.baritone.IPath;
import com.dwinovo.numen.pathing.search.baritone.Move;
import com.dwinovo.numen.pathing.search.baritone.MutableMoveResult;
import com.dwinovo.numen.pathing.search.baritone.calc.openset.BinaryHeapOpenSet;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    /**
     * How many times a move may walk into an unloaded chunk before the search
     * gives up. Replaces Baritone's pathingMaxChunkBorderFetch setting.
     */
    private static final int PATHING_MAX_CHUNK_BORDER_FETCH = 128;

    private final Favoring favoring;
    private final CalculationContext calcContext;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.minY();
        int height = calcContext.height();
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + primaryTimeout;
        long failureTimeoutTime = startTime + failureTimeout;
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        double minimumImprovement = MIN_IMPROVEMENT;
        Move[] allMoves = calcContext.moves().toArray(new Move[0]);
        while (!openSet.isEmpty() && numEmptyChunk < PATHING_MAX_CHUNK_BORDER_FETCH && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            for (Move moves : allMoves) {
                int newX = currentNode.x + moves.xOffset();
                int newZ = currentNode.z + moves.zOffset();
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ()) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ() && !calcContext.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset() > height || currentNode.y + moves.yOffset() < minY) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s calculated implausible cost %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, actionCost));
                }
                // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
                if (moves.dynamicXZ() && !calcContext.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                if (!moves.dynamicXZ() && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at x z %s %s instead of %s %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.x, res.z, newX, newZ));
                }
                if (!moves.dynamicY() && res.y != currentNode.y + moves.yOffset()) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at y %s instead of %s",
                            moves, currentNode.x, currentNode.y, currentNode.z, res.y, currentNode.y + moves.yOffset()));
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (isFavoring) {
                    // see issue #18
                    actionCost *= favoring.calculate(hashCode);
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        return bestSoFar(true, numNodes);
    }
}
