/*
 * Tests for the stuck/backtrack recovery helper, run on the same synthetic
 * grid style as BaritoneAStarTest: no Minecraft world, just a small passable
 * plane and the vendored A*.
 */
package com.dwinovo.numen.pathing.search.baritone.recover;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.search.baritone.ActionCosts;
import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.CalculationContext;
import com.dwinovo.numen.pathing.search.baritone.Favoring;
import com.dwinovo.numen.pathing.search.baritone.Goal;
import com.dwinovo.numen.pathing.search.baritone.IPath;
import com.dwinovo.numen.pathing.search.baritone.Move;
import com.dwinovo.numen.pathing.search.baritone.MutableMoveResult;
import com.dwinovo.numen.pathing.search.baritone.PathCalculationResult;
import com.dwinovo.numen.pathing.search.baritone.calc.AStarPathFinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two behaviors the helper mirrors from Baritone:
 * <ol>
 *   <li>a search that cannot reach the goal still yields its best partial path
 *       ({@code bestSoFar} / {@code pathToMostRecentNodeConsidered});</li>
 *   <li>a repeatedly stuck execution yields a recompute request from the
 *       current node ({@code pathStart} semantics).</li>
 * </ol>
 */
class StuckRecoveryTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

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

    private static AStarPathFinder finder(Grid grid, int sx, int sz, int gx, int gz) {
        Goal goal = new Cell(gx, gz);
        return new AStarPathFinder(new BetterBlockPos(sx, 0, sz), sx, 0, sz, goal, Favoring.NONE, grid);
    }

    // ==================== partial / backtrack ====================

    /**
     * A long corridor is walled off just before the goal. A* still reports the
     * best-so-far partial segment, and the helper hands it back unchanged.
     */
    @Test
    void unreachableGoalReturnsThePartialBestSoFar() {
        Grid grid = new Grid(16, 2);
        for (int x = 0; x < 16; x++) {
            grid.block(x, 1);
        }
        grid.block(10, 0);

        AStarPathFinder finder = finder(grid, 0, 0, 13, 0);
        PathCalculationResult result = finder.calculate(60_000, 60_000);

        // Baritone calls this SUCCESS_SEGMENT: a path toward the goal that does
        // not reach it. bestSoFar only reports it once past MIN_DIST_PATH (5).
        assertEquals(PathCalculationResult.Type.SUCCESS_SEGMENT, result.type());
        assertNotNull(result.path());

        StuckRecovery recovery = new StuckRecovery(finder);
        Recovery out = recovery.onResult(result);

        Recovery.Fallback fallback = assertInstanceOf(Recovery.Fallback.class, out);
        assertSame(result, fallback.result(), "a result carrying a path is returned as-is");
        IPath path = fallback.result().path();
        assertEquals(new BetterBlockPos(0, 0, 0), path.getSrc());
        assertFalse(path.getGoal().isInGoal(path.getDest().x, path.getDest().y, path.getDest().z),
                "the partial must not claim the goal");
        assertEquals(9, path.getDest().x, "the partial stops one cell before the wall");
    }

    /**
     * A short dead end (shorter than MIN_DIST_PATH): A* reports a bare FAILURE,
     * but {@code pathToMostRecentNodeConsidered()} still holds a walkable
     * backtrack, which the helper salvages.
     */
    @Test
    void aBareFailureStillYieldsTheMostRecentNode() {
        Grid grid = new Grid(8, 2);
        for (int x = 0; x < 8; x++) {
            grid.block(x, 1);
        }
        grid.block(4, 0);

        AStarPathFinder finder = finder(grid, 0, 0, 7, 0);
        PathCalculationResult result = finder.calculate(60_000, 60_000);

        assertEquals(PathCalculationResult.Type.FAILURE, result.type());
        assertNull(result.path());
        assertTrue(finder.bestPathSoFar().isEmpty(), "the dead end is too short for bestSoFar");

        Recovery out = new StuckRecovery(finder).onResult(result);

        Recovery.Fallback fallback = assertInstanceOf(Recovery.Fallback.class, out);
        assertEquals(PathCalculationResult.Type.SUCCESS_SEGMENT, fallback.result().type());
        IPath path = fallback.result().path();
        assertNotNull(path);
        assertTrue(path.length() >= StuckRecovery.MIN_PARTIAL_POSITIONS);
        assertEquals(new BetterBlockPos(0, 0, 0), path.getSrc());
        assertEquals(new BetterBlockPos(3, 0, 0), path.getDest(),
                "the backtrack ends at the last node the search considered");
    }

    /**
     * Nothing to salvage: the start is fully enclosed, so the most-recent node
     * is the start itself (a one-position path), which must not be handed out.
     */
    @Test
    void anEnclosedStartWithNothingToSalvageYieldsNone() {
        Grid grid = new Grid(1, 1);

        AStarPathFinder finder = finder(grid, 0, 0, 5, 0);
        PathCalculationResult result = finder.calculate(60_000, 60_000);

        assertEquals(PathCalculationResult.Type.FAILURE, result.type());
        IPath recent = finder.pathToMostRecentNodeConsidered().orElseThrow();
        assertEquals(1, recent.length(), "only the start was ever considered");

        Recovery out = new StuckRecovery(finder).onResult(result);

        assertInstanceOf(Recovery.None.class, out);
    }

    @Test
    void aNullResultWithNoFinderYieldsNone() {
        assertInstanceOf(Recovery.None.class, new StuckRecovery().onResult(null));
        assertInstanceOf(Recovery.None.class,
                new StuckRecovery().onResult(new PathCalculationResult(PathCalculationResult.Type.FAILURE)));
    }

    // ==================== stuck / recompute ====================

    /**
     * Repeated stuck signals produce one recompute request carrying the node
     * the body currently occupies, then start counting again.
     */
    @Test
    void repeatedStuckSignalsRequestARecomputeFromTheCurrentNode() {
        StuckRecovery recovery = new StuckRecovery(null, StuckRecovery.DEFAULT_STUCK_STRIKES);
        BetterBlockPos here = new BetterBlockPos(4, 64, -7);

        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, here));
        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, here));

        Recovery out = recovery.onStuck(true, here);

        Recovery.Recompute recompute = assertInstanceOf(Recovery.Recompute.class, out);
        assertEquals(here, recompute.from());
        assertEquals(0, recovery.strikes(), "the count resets once the request fires");
        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, here),
                "the request is one-shot; a single tick does not fire again");
    }

    /** A tick where execution is moving clears the accumulated strike count. */
    @Test
    void aNonStuckTickClearsTheStrikeCount() {
        StuckRecovery recovery = new StuckRecovery(null, 3);
        BetterBlockPos here = new BetterBlockPos(0, 0, 0);

        recovery.onStuck(true, here);
        recovery.onStuck(true, here);
        assertEquals(2, recovery.strikes());

        assertInstanceOf(Recovery.None.class, recovery.onStuck(false, here));
        assertEquals(0, recovery.strikes());

        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, here));
        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, here));
        assertInstanceOf(Recovery.Recompute.class, recovery.onStuck(true, here));
    }

    /** Without a current node there is nowhere to replan from, so nothing fires. */
    @Test
    void aStuckSignalWithoutACurrentNodeYieldsNone() {
        StuckRecovery recovery = new StuckRecovery(null, 1);

        assertInstanceOf(Recovery.None.class, recovery.onStuck(true, null));
        assertEquals(0, recovery.strikes());
    }
}
