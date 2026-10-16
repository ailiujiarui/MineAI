package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 抬高估价(LSS-LRTA* 的学习一步):从边界往里的最短路;不经搜索。 */
class LearningTest {

    /** 一条线 0-1-2-3,每步价 1,0 到 2 展开过,3 是边界,各节点的估价都很低(像是死路里估价看不见墙)。 */
    @Test
    void aDeadEndLineIsRaisedToTheDistanceToTheBoundaryPlusItsEstimate() {
        boolean[] expanded = {true, true, true, false};
        double[] h = {0, 0, 0, 5};
        int[] from = {0, 1, 2};
        int[] to = {1, 2, 3};
        double[] cost = {1, 1, 1};
        double[] raised = Learning.raise(expanded, h, 4, from, to, cost, 3);
        assertArrayEquals(new double[] {8, 7, 6, 5}, raised, 1e-9);
    }

    @Test
    void theCheapestWayOutToTheBoundaryCounts() {
        // 0 能走到边界 2(估价 10,一步)或边界 3(估价 1,两步经过 1)
        boolean[] expanded = {true, true, false, false};
        double[] h = {0, 0, 10, 1};
        double[] raised = Learning.raise(expanded, h, 4, new int[] {0, 0, 1}, new int[] {2, 1, 3}, new double[] {1, 1, 1}, 3);
        assertEquals(3, raised[0], 1e-9, "经 1 走到 3:1 + 1 + 1");
        assertEquals(2, raised[1], 1e-9);
    }

    @Test
    void anEstimateIsNeverLowered() {
        boolean[] expanded = {true, false};
        double[] raised = Learning.raise(expanded, new double[] {50, 3}, 2, new int[] {0}, new int[] {1}, new double[] {1}, 1);
        assertEquals(50, raised[0], "走到边界只要 4,原来的估价 50 更高,不降");
    }

    @Test
    void withNoBoundaryEveryExpandedNodeIsUnreachable() {
        boolean[] expanded = {true, true};
        double[] raised = Learning.raise(expanded, new double[] {0, 0}, 2, new int[] {0, 1}, new int[] {1, 0}, new double[] {1, 1}, 2);
        assertTrue(Double.isInfinite(raised[0]) && Double.isInfinite(raised[1]));
    }
}
