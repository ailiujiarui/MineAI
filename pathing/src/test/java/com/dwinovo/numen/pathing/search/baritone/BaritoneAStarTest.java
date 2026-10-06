/*
 * Tests for the vendored Baritone A* search core, run on a synthetic grid with
 * no Minecraft world.
 */
package com.dwinovo.numen.pathing.search.baritone;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.search.baritone.calc.AStarPathFinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the vendored A* on a small 2D grid: it must route around a wall, and
 * report failure when the goal is walled off.
 */
class BaritoneAStarTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    /** A grid of passable cells at y = 0; a cell is blocked or it is not. */
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

        Grid wall(int x, int z0, int z1) {
            for (int z = z0; z <= z1; z++) {
                block(x, z);
            }
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

    /** One orthogonal step of cost 1; blocked or out-of-bounds costs infinity. */
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

    private static PathCalculationResult run(CalculationContext context, int sx, int sz, int gx, int gz) {
        Goal goal = new Cell(gx, gz);
        AStarPathFinder finder = new AStarPathFinder(new BetterBlockPos(sx, 0, sz), sx, 0, sz, goal, Favoring.NONE, context);
        return finder.calculate(60_000, 60_000);
    }

    @Test
    void routesAroundAWall() {
        Grid grid = new Grid(12, 6).wall(5, 0, 4);

        PathCalculationResult result = run(grid, 0, 0, 10, 0);

        assertEquals(PathCalculationResult.Type.SUCCESS_TO_GOAL, result.type());
        List<BetterBlockPos> path = result.path().positions();
        assertEquals(new BetterBlockPos(0, 0, 0), path.get(0), "starts at the start");
        assertEquals(new BetterBlockPos(10, 0, 0), path.get(path.size() - 1), "ends in the goal");
        for (int i = 0; i < path.size(); i++) {
            BetterBlockPos p = path.get(i);
            assertTrue(grid.passable(p.x, p.y, p.z), "path crosses a blocked cell: " + p);
            if (i > 0) {
                BetterBlockPos q = path.get(i - 1);
                assertEquals(1, Math.abs(p.x - q.x) + Math.abs(p.z - q.z), "steps must be adjacent: " + q + " -> " + p);
            }
        }
        assertTrue(path.size() - 1 > 10, "the wall forces a detour, not the straight line: " + (path.size() - 1) + " steps");
    }

    @Test
    void anEnclosedGoalIsUnreachable() {
        // Only a short dead-end corridor is passable, so no node is far enough
        // from the start to be a partial path either.
        Grid grid = new Grid(8, 2);
        for (int x = 0; x < 8; x++) {
            grid.block(x, 1);
        }
        grid.block(4, 0);

        PathCalculationResult result = run(grid, 0, 0, 7, 0);

        assertEquals(PathCalculationResult.Type.FAILURE, result.type());
        assertNull(result.path());
    }

    @Test
    void theHeuristicPullsTheSearchStraightAcrossOpenGround() {
        Grid grid = new Grid(12, 4);

        PathCalculationResult result = run(grid, 0, 0, 10, 0);

        assertEquals(PathCalculationResult.Type.SUCCESS_TO_GOAL, result.type());
        List<BetterBlockPos> path = result.path().positions();
        assertEquals(10, path.size() - 1, "open ground: the straight line is optimal");
        for (BetterBlockPos p : path) {
            assertEquals(0, p.z, "no need to leave z = 0: " + p);
        }
    }
}
