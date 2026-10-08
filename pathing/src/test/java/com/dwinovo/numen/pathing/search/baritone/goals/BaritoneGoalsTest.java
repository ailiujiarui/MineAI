/*
 * Tests for the reconstructed Baritone goal family: the reach test of each
 * shape, and that the location heuristics never overestimate the true step
 * cost on a small open grid.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.search.baritone.Goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneGoalsTest {

    private static final double EPSILON = 1.0E-9;

    // ==================== isInGoal ====================

    @Test
    void blockGoalContainsExactlyItsCell() {
        GoalBlock goal = new GoalBlock(0, 64, 0);

        assertTrue(goal.isInGoal(0, 64, 0));
        assertFalse(goal.isInGoal(1, 64, 0));
        assertFalse(goal.isInGoal(0, 65, 0));
        assertFalse(goal.isInGoal(0, 64, 1));
    }

    @Test
    void nearGoalContainsTheBallOfTheGivenRange() {
        GoalNear rangeTwo = new GoalNear(0, 0, 0, 2);

        assertTrue(rangeTwo.isInGoal(0, 0, 0), "the center itself");
        assertTrue(rangeTwo.isInGoal(2, 0, 0), "on the axis at the range");
        assertTrue(rangeTwo.isInGoal(1, 1, 1), "1+1+1 = 3 <= 4");
        assertTrue(rangeTwo.isInGoal(2, 0, 0));
        assertFalse(rangeTwo.isInGoal(3, 0, 0), "9 > 4");
        assertFalse(rangeTwo.isInGoal(2, 1, 0), "5 > 4");
        assertFalse(rangeTwo.isInGoal(0, 2, 1), "5 > 4");

        GoalNear rangeZero = new GoalNear(0, 0, 0, 0);
        assertTrue(rangeZero.isInGoal(0, 0, 0));
        assertFalse(rangeZero.isInGoal(1, 0, 0));
    }

    @Test
    void xzGoalContainsTheWholeColumnAtAnyHeight() {
        GoalXZ goal = new GoalXZ(3, 7);

        assertTrue(goal.isInGoal(3, -64, 7));
        assertTrue(goal.isInGoal(3, 0, 7));
        assertTrue(goal.isInGoal(3, 320, 7));
        assertFalse(goal.isInGoal(4, 0, 7), "x differs");
        assertFalse(goal.isInGoal(3, 0, 8), "z differs");
    }

    @Test
    void yLevelGoalContainsTheWholeLayer() {
        GoalYLevel goal = new GoalYLevel(12);

        assertTrue(goal.isInGoal(0, 12, 0));
        assertTrue(goal.isInGoal(-50, 12, 99));
        assertFalse(goal.isInGoal(0, 11, 0));
    }

    @Test
    void getToBlockContainsTheCellsTheHandCanReachFrom() {
        GoalGetToBlock goal = new GoalGetToBlock(0, 0, 0);

        // The block, its sides, its top, and the one or two cells below.
        assertTrue(goal.isInGoal(0, 0, 0));
        assertTrue(goal.isInGoal(1, 0, 0));
        assertTrue(goal.isInGoal(-1, 0, 0));
        assertTrue(goal.isInGoal(0, 0, 1));
        assertTrue(goal.isInGoal(0, 0, -1));
        assertTrue(goal.isInGoal(0, 1, 0));
        assertTrue(goal.isInGoal(0, -1, 0));
        assertTrue(goal.isInGoal(0, -2, 0));
        assertFalse(goal.isInGoal(2, 0, 0));
        assertFalse(goal.isInGoal(1, 1, 0));
    }

    @Test
    void twoBlocksGoalAcceptsTheCellAndTheOneBelow() {
        GoalTwoBlocks goal = new GoalTwoBlocks(0, 5, 0);

        assertTrue(goal.isInGoal(0, 5, 0));
        assertTrue(goal.isInGoal(0, 4, 0));
        assertFalse(goal.isInGoal(0, 6, 0));
        assertFalse(goal.isInGoal(1, 5, 0));
    }

    // ==================== admissibility ====================

    @Test
    void blockHeuristicNeverOverestimatesTheTrueCost() {
        assertAdmissible(new GoalBlock(0, 0, 0), 6, 4);
    }

    @Test
    void xzHeuristicNeverOverestimatesTheTrueCost() {
        assertAdmissible(new GoalXZ(0, 0), 6, 4);
    }

    @Test
    void yLevelHeuristicNeverOverestimatesTheTrueCost() {
        assertAdmissible(new GoalYLevel(0), 3, 4);
    }

    @Test
    void compositeHeuristicIsTheClosestMemberAndStaysAdmissible() {
        GoalComposite composite = new GoalComposite(new GoalBlock(3, 0, 0), new GoalBlock(-2, 0, 0));

        assertTrue(composite.isInGoal(3, 0, 0));
        assertTrue(composite.isInGoal(-2, 0, 0));
        assertEquals(new GoalBlock(-2, 0, 0).heuristic(0, 0, 0), composite.heuristic(0, 0, 0), EPSILON);
        assertAdmissible(composite, 6, 3);
    }

    /**
     * Runs an exhaustive shortest-path relaxation on a small open grid whose
     * moves cost the same per-block amounts the heuristics are built from, and
     * checks the goal's heuristic never exceeds the true remaining cost.
     *
     * <p>Every orthogonal horizontal step costs {@link HeuristicCosts#COST_PER_BLOCK},
     * every diagonal step the same times sqrt(2), a step up costs a jump and a
     * step down a two-block fall half. On such a grid the octile/vertical
     * heuristic is exactly the cost of an unobstructed run, so this is a tight
     * check.
     */
    private static void assertAdmissible(Goal goal, int radiusXZ, int radiusY) {
        int sizeX = 2 * radiusXZ + 1;
        int sizeZ = sizeX;
        int sizeY = 2 * radiusY + 1;
        double[] dist = new double[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);

        for (int xi = 0; xi < sizeX; xi++) {
            for (int yi = 0; yi < sizeY; yi++) {
                for (int zi = 0; zi < sizeZ; zi++) {
                    int x = xi - radiusXZ;
                    int y = yi - radiusY;
                    int z = zi - radiusXZ;
                    if (goal.isInGoal(x, y, z)) {
                        dist[index(xi, yi, zi, sizeY, sizeZ)] = 0;
                    }
                }
            }
        }

        double c = HeuristicCosts.COST_PER_BLOCK;
        double d = c * Math.sqrt(2);
        double[][] moves = {
                {1, 0, 0, c}, {-1, 0, 0, c}, {0, 0, 1, c}, {0, 0, -1, c},
                {1, 0, 1, d}, {1, 0, -1, d}, {-1, 0, 1, d}, {-1, 0, -1, d},
                {0, 1, 0, HeuristicCosts.JUMP_ONE_BLOCK}, {0, -1, 0, HeuristicCosts.DESCEND_ONE_BLOCK},
        };

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int xi = 0; xi < sizeX; xi++) {
                for (int yi = 0; yi < sizeY; yi++) {
                    for (int zi = 0; zi < sizeZ; zi++) {
                        int x = xi - radiusXZ;
                        int y = yi - radiusY;
                        int z = zi - radiusXZ;
                        for (double[] move : moves) {
                            int ox = (int) move[0];
                            int oy = (int) move[1];
                            int oz = (int) move[2];
                            int qx = xi + ox;
                            int qy = yi + oy;
                            int qz = zi + oz;
                            if (qx < 0 || qx >= sizeX || qy < 0 || qy >= sizeY || qz < 0 || qz >= sizeZ) {
                                continue;
                            }
                            double candidate = move[3] + dist[index(qx, qy, qz, sizeY, sizeZ)];
                            int here = index(xi, yi, zi, sizeY, sizeZ);
                            if (candidate < dist[here] - EPSILON) {
                                dist[here] = candidate;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        for (int xi = 0; xi < sizeX; xi++) {
            for (int yi = 0; yi < sizeY; yi++) {
                for (int zi = 0; zi < sizeZ; zi++) {
                    int x = xi - radiusXZ;
                    int y = yi - radiusY;
                    int z = zi - radiusXZ;
                    double h = goal.heuristic(x, y, z);
                    double trueCost = dist[index(xi, yi, zi, sizeY, sizeZ)];
                    assertTrue(trueCost != Double.POSITIVE_INFINITY, "no route to the goal from " + x + "," + y + "," + z);
                    assertTrue(h <= trueCost + EPSILON,
                            "heuristic " + h + " overestimates true cost " + trueCost + " at " + x + "," + y + "," + z);
                }
            }
        }
    }

    private static int index(int xi, int yi, int zi, int sizeY, int sizeZ) {
        return (xi * sizeY + yi) * sizeZ + zi;
    }
}
