package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 一趟的总预算:与估价成正比、用完收场、一次搜索再没有进展也要花预算;不经 Driver。 */
class AllowanceTest {

    private static final double SEGMENT = 100;

    @Test
    void theTotalGrowsWithTheEstimateInWholeSegments() {
        Allowance near = new Allowance(0);
        near.begun(30, SEGMENT);
        Allowance far = new Allowance(0);
        far.begun(1000, SEGMENT);
        assertEquals(Allowance.MIN_SEGMENTS * Allowance.SEGMENT_NODES, drain(near), "近处的也至少够搜几段");
        assertEquals(10 * Allowance.SLACK * Allowance.SEGMENT_NODES, drain(far), "远的按走完要几段、留余量");
    }

    @Test
    void aSpecifiedTotalIsNotRescaledByTheEstimate() {
        Allowance fixed = new Allowance(500);
        fixed.begun(100_000, SEGMENT);
        assertEquals(500, drain(fixed));
    }

    @Test
    void aSearchThatExpandedNothingStillCosts() {
        Allowance allowance = new Allowance(2);
        allowance.spend(0);
        assertFalse(allowance.spent());
        allowance.spend(0);
        assertTrue(allowance.spent(), "原地不动的搜索也要花预算,不然会一直搜下去");
    }

    @Test
    void aSegmentNeverSearchesMoreThanWhatIsLeft() {
        Allowance allowance = new Allowance(Allowance.SEGMENT_NODES + 10);
        assertEquals(Allowance.SEGMENT_NODES, allowance.segment());
        allowance.spend(Allowance.SEGMENT_NODES);
        assertEquals(10, allowance.segment());
    }

    @Test
    void restartingGivesTheNewGoalItsOwnTotal() {
        Allowance allowance = new Allowance(0);
        allowance.begun(1000, SEGMENT);
        allowance.spend(5);
        allowance.restart();
        allowance.begun(30, SEGMENT);
        assertEquals(Allowance.MIN_SEGMENTS * Allowance.SEGMENT_NODES, drain(allowance));
    }

    /** 一次花一个节点,数能花多少个才用完。 */
    private static int drain(Allowance allowance) {
        int n = 0;
        while (!allowance.spent() && n < 10_000_000) {
            allowance.spend(1);
            n++;
        }
        return n + 0;
    }
}
