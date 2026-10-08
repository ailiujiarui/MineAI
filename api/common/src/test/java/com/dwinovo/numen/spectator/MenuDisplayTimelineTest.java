package com.dwinovo.numen.spectator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MenuDisplayTimelineTest {
    @Test
    void sameTickCloseHoldsForASecondFromApplicationRatherThanArrival() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        assertTrue(timeline.accept(1, 1, true, false));
        assertTrue(timeline.accept(1, 2, false, false));
        assertTrue(timeline.accept(1, 3, false, true));
        assertFalse(timeline.expired(5000));
        timeline.applied(5000);
        assertFalse(timeline.expired(5999));
        assertTrue(timeline.expired(6000));
    }

    @Test
    void closedFrameRejectsClearUpdatesAndDuplicateCloseCannotExtendItsLife() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        assertTrue(timeline.accept(1, 1, true, false));
        assertTrue(timeline.accept(1, 2, false, true));
        timeline.applied(1000);
        assertFalse(timeline.accept(1, 3, false, false));
        assertFalse(timeline.accept(1, 4, false, true));
        timeline.applied(1500);
        assertTrue(timeline.expired(2000));
    }

    @Test
    void latestMenuImmediatelyReplacesCloseDelayAndRejectsEveryOldPacket() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        assertTrue(timeline.accept(1, 1, true, false));
        assertTrue(timeline.accept(1, 2, false, true));
        timeline.applied(1000);
        assertTrue(timeline.accept(2, 1, true, false));
        assertFalse(timeline.expired(2000));
        assertFalse(timeline.accept(1, 999, true, false));
        assertFalse(timeline.accept(1, 1000, false, true));
        assertTrue(timeline.accept(2, 2, false, false));
    }

    @Test
    void dismissalKeepsTheHighWaterMarkUntilTheViewingSessionResets() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        assertTrue(timeline.accept(8, 1, true, false));
        timeline.dismiss();
        assertFalse(timeline.accept(8, 2, false, false));
        assertTrue(timeline.accept(9, 1, true, false));
        timeline.reset();
        assertTrue(timeline.accept(1, 1, true, false));
    }

    @Test
    void bufferedCloseMayCreateTheMenuWhenTheEntityArrivesAndOlderRevisionsStayRejected() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        assertTrue(timeline.accept(3, 7, false, true));
        assertFalse(timeline.accept(3, 2, true, false));
        timeline.applied(1500);
        assertTrue(timeline.expired(2500));
    }
}
