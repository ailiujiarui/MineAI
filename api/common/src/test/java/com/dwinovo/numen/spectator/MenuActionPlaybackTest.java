package com.dwinovo.numen.spectator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuActionPlaybackTest {
    @Test
    void sameTickFramesEachRemainVisibleFromTheirActualApplication() {
        MenuActionPlayback<String> playback = new MenuActionPlayback<>();
        playback.replace(List.of("before", "first move", "second move"));
        assertEquals("before", playback.frameToApply(1000));
        assertEquals("before", playback.frameToApply(5000));
        playback.applied(5000);
        assertNull(playback.frameToApply(5199));
        assertEquals("first move", playback.frameToApply(5200));
        playback.applied(5300);
        assertNull(playback.frameToApply(5499));
        assertEquals("second move", playback.frameToApply(5500));
        playback.applied(5500);
        assertFalse(playback.finished(5699));
        assertTrue(playback.finished(5700));
    }

    @Test
    void slowTicksDoNotSkipIntermediateMoves() {
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        playback.replace(List.of(0, 1, 2, 3));
        assertEquals(0, playback.frameToApply(0));
        playback.applied(0);
        assertEquals(1, playback.frameToApply(10000));
        playback.applied(10000);
        assertNull(playback.frameToApply(10000));
        assertEquals(2, playback.frameToApply(20000));
        playback.applied(20000);
        assertFalse(playback.lastFrame());
    }

    @Test
    void replacementImmediatelyDropsOldFramesAndTheirClock() {
        MenuActionPlayback<String> playback = new MenuActionPlayback<>();
        playback.replace(List.of("old before", "old after"));
        assertEquals("old before", playback.frameToApply(1000));
        playback.applied(1000);
        playback.replace(List.of("new before", "new after"));
        assertEquals("new before", playback.frameToApply(1001));
        playback.applied(1001);
        assertNull(playback.frameToApply(1200));
        assertEquals("new after", playback.frameToApply(1201));
    }

    @Test
    void finalSnapshotStartsTheAutomaticCloseDelay() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        assertTrue(timeline.accept(1, 1, true, true));
        playback.replace(List.of(0, 1, 2));
        assertEquals(0, playback.frameToApply(5000));
        playback.applied(5000);
        assertFalse(playback.lastFrame());
        assertFalse(timeline.expired(10000));
        assertEquals(1, playback.frameToApply(10000));
        playback.applied(10000);
        assertEquals(2, playback.frameToApply(10200));
        playback.applied(10200);
        assertTrue(playback.lastFrame());
        timeline.applied(10200);
        assertFalse(timeline.expired(11199));
        assertTrue(timeline.expired(11200));
    }

    @Test
    void manualInventoryRestoresLiveDataOnlyAfterItsFinalSnapshotWasVisible() {
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        playback.replace(List.of(0, 1));
        assertEquals(0, playback.frameToApply(0));
        playback.applied(0);
        assertEquals(1, playback.frameToApply(200));
        playback.applied(200);
        assertFalse(playback.finished(399));
        assertTrue(playback.finished(400));
    }

    @Test
    void nativeCloseWaitsForPlaybackThenHoldsItsOwnClosingSnapshot() {
        MenuDisplayTimeline timeline = new MenuDisplayTimeline();
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        assertTrue(timeline.accept(1, 1, true, false));
        assertTrue(timeline.accept(1, 2, true, false));
        playback.replace(List.of(0, 1));
        assertEquals(0, playback.frameToApply(1000));
        playback.applied(1000);
        assertTrue(timeline.accept(1, 3, false, true));
        assertFalse(timeline.accept(1, 4, false, false));
        assertFalse(timeline.expired(5000));
        assertEquals(1, playback.frameToApply(5000));
        playback.applied(5000);
        assertFalse(playback.finished(5199));
        assertTrue(playback.finished(5200));
        playback.clear();
        timeline.applied(5200);
        assertFalse(timeline.expired(6199));
        assertTrue(timeline.expired(6200));
    }

    @Test
    void duplicateApplicationDoesNotExtendAFrame() {
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        playback.replace(List.of(0, 1));
        playback.frameToApply(1000);
        playback.applied(1000);
        playback.applied(1100);
        assertEquals(1, playback.frameToApply(1200));
    }

    @Test
    void clearingOnDismissOrSessionChangeDropsEveryPendingFrame() {
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        playback.replace(List.of(0, 1));
        playback.frameToApply(1000);
        playback.applied(1000);
        playback.clear();
        assertFalse(playback.active());
        assertFalse(playback.lastFrame());
        assertFalse(playback.finished(99999));
        assertNull(playback.frameToApply(99999));
    }

    @Test
    void incomingListMutationCannotChangeTheCurrentAction() {
        MenuActionPlayback<Integer> playback = new MenuActionPlayback<>();
        List<Integer> incoming = new ArrayList<>(List.of(0, 1));
        playback.replace(incoming);
        incoming.clear();
        assertEquals(0, playback.frameToApply(1000));
        playback.applied(1000);
        assertEquals(1, playback.frameToApply(1200));
    }
}
