/*
 * Regression guard for the `numen.move.go no_path` failure mode the bench saw
 * (see docs/bench.md §五, the 20261006-132531 summary: 40/200 calls failed
 * with no_path). Each world below is a small synthetic graph on which a
 * straight-line / greedy walk gives up with no path, while the vendored
 * Baritone A* must still find a route. The tests call the vendored
 * AStarPathFinder directly, so they pass before the live seam is wired; when
 * step 2 points the live pathfinder at this search, the same worlds are the
 * first thing to re-run.
 */
package com.dwinovo.numen.pathing.search.baritone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.search.baritone.calc.AStarPathFinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoPathRegressionTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** A passable plane at y = 0; a cell is either blocked or it is not. */
    private static final class Grid implements CalculationContext {

        private final int width;
        private final int depth;
        private final boolean[] blocked;
        private final List<Move> moves;

        Grid(int width, int depth) {
            this.width = width;
            this.depth = depth;
            this.blocked = new boolean[width * depth];
            List<Move> four = new ArrayList<>();
            four.add(new Step(1, 0));
            four.add(new Step(-1, 0));
            four.add(new Step(0, 1));
            four.add(new Step(0, -1));
            this.moves = List.copyOf(four);
        }

        Grid block(int x, int z) {
            blocked[index(x, z)] = true;
            return this;
        }

        Grid open(int x, int z) {
            blocked[index(x, z)] = false;
            return this;
        }

        Grid blockAll() {
            Arrays.fill(blocked, true);
            return this;
        }

        boolean passable(int x, int y, int z) {
            return y == 0 && x >= 0 && x < width && z >= 0 && z < depth && !blocked[index(x, z)];
        }

        private int index(int x, int z) {
            return z * width + x;
        }

        @Override
        public int minY() {
            return MIN_Y;
        }

        @Override
        public int height() {
            return HEIGHT;
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean entirelyContains(int x, int z) {
            return x >= 0 && x < width && z >= 0 && z < depth;
        }

        @Override
        public List<Move> moves() {
            return moves;
        }
    }

    /** One orthogonal step of cost 1; a blocked or out-of-bounds cell costs infinity. */
    private record Step(int xOffset, int zOffset) implements Move {

        @Override
        public int yOffset() {
            return 0;
        }

        @Override
        public boolean dynamicXZ() {
            return false;
        }

        @Override
        public boolean dynamicY() {
            return false;
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            int nx = x + xOffset;
            int nz = z + zOffset;
            result.x = nx;
            result.y = y;
            result.z = nz;
            result.cost = ((Grid) context).passable(nx, y, nz) ? 1.0 : ActionCosts.COST_INF;
        }
    }

    /** Reach the single cell {@code (x, z)}; heuristic is Manhattan distance. */
    private record Cell(int x, int z) implements Goal {

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return Math.abs(x - this.x) + Math.abs(z - this.z);
        }
    }

    private static PathCalculationResult run(Grid grid, int sx, int sz, int gx, int gz) {
        Goal goal = new Cell(gx, gz);
        AStarPathFinder finder = new AStarPathFinder(new BetterBlockPos(sx, 0, sz), sx, 0, sz, goal, Favoring.NONE, grid);
        return finder.calculate(60_000, 60_000);
    }

    /**
     * The naive baseline: hill-climb on Manhattan distance, only ever stepping to a
     * neighbour strictly closer to the goal. Returns {@code null} when every move
     * would increase the distance. This is the behaviour `no_path` came from.
     */
    private static List<BetterBlockPos> greedyWalk(Grid grid, int sx, int sz, int gx, int gz) {
        List<BetterBlockPos> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int x = sx;
        int z = sz;
        path.add(new BetterBlockPos(x, 0, z));
        seen.add(key(x, z));
        while (x != gx || z != gz) {
            int best = Math.abs(x - gx) + Math.abs(z - gz);
            int bx = Integer.MIN_VALUE;
            int bz = Integer.MIN_VALUE;
            for (int[] dir : DIRECTIONS) {
                int nx = x + dir[0];
                int nz = z + dir[1];
                if (!grid.passable(nx, 0, nz) || seen.contains(key(nx, nz))) {
                    continue;
                }
                int dist = Math.abs(nx - gx) + Math.abs(nz - gz);
                if (dist < best) {
                    bx = nx;
                    bz = nz;
                    best = dist;
                }
            }
            if (bx == Integer.MIN_VALUE) {
                return null;
            }
            x = bx;
            z = bz;
            path.add(new BetterBlockPos(x, 0, z));
            seen.add(key(x, z));
        }
        return path;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Asserts the A* produces an optimal, contiguous, in-bounds path and also that the greedy baseline does not. */
    private static void assertAStarBeatsGreedy(Grid grid, int sx, int sz, int gx, int gz, int optimalSteps) {
        assertNull(greedyWalk(grid, sx, sz, gx, gz), "the naive greedy walk was expected to give up here");

        PathCalculationResult result = run(grid, sx, sz, gx, gz);

        assertEquals(PathCalculationResult.Type.SUCCESS_TO_GOAL, result.type(), "A* must reach the goal, not report no_path");
        List<BetterBlockPos> path = result.path().positions();
        assertEquals(new BetterBlockPos(sx, 0, sz), path.get(0), "starts at the start");
        assertEquals(new BetterBlockPos(gx, 0, gz), path.get(path.size() - 1), "ends in the goal");
        assertEquals(optimalSteps, path.size() - 1, "the detour must be the cheapest one");
        for (int i = 0; i < path.size(); i++) {
            BetterBlockPos p = path.get(i);
            assertTrue(grid.passable(p.x, p.y, p.z), "path crosses a blocked cell: " + p);
            if (i > 0) {
                BetterBlockPos q = path.get(i - 1);
                assertEquals(1, Math.abs(p.x - q.x) + Math.abs(p.z - q.z), "steps must be adjacent: " + q + " -> " + p);
            }
        }
    }

    @Test
    void aWallWhoseGapIsOffTheStraightLineStopsTheGreedyWalk() {
        Grid grid = new Grid(14, 8);
        for (int z = 0; z <= 3; z++) {
            grid.block(6, z);
        }

        assertAStarBeatsGreedy(grid, 0, 1, 12, 1, 18);
    }

    @Test
    void aSerpentineCorridorAbandonedByTheGreedyWalkIsFollowed() {
        Grid grid = new Grid(12, 7).blockAll();
        for (int x = 1; x <= 10; x++) {
            grid.open(x, 1);
        }
        for (int z = 2; z <= 5; z++) {
            grid.open(10, z);
        }
        for (int x = 2; x <= 9; x++) {
            grid.open(x, 5);
        }
        for (int z = 3; z <= 4; z++) {
            grid.open(2, z);
        }

        assertAStarBeatsGreedy(grid, 1, 1, 2, 3, 23);
    }

    @Test
    void aPocketWhoseMouthFacesAwayFromTheStartIsEnteredFromBehind() {
        Grid grid = new Grid(15, 11);
        for (int z = 2; z <= 6; z++) {
            grid.block(4, z);
            grid.block(10, z);
        }
        for (int x = 4; x <= 10; x++) {
            grid.block(x, 6);
        }

        assertAStarBeatsGreedy(grid, 7, 9, 7, 2, 17);
    }
}
