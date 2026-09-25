package com.dwinovo.numen.agent.recover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTrackerTest {

    @Test
    void theSameSignatureSeenEnoughTimesIsStalled() {
        ProgressTracker tracker = new ProgressTracker(2);
        assertFalse(tracker.observe("iron:0"), "第一次看到不算停滞");
        assertTrue(tracker.observe("iron:0"), "第二次连着一样,判停滞");
    }

    @Test
    void aChangedSignatureResetsTheStreak() {
        ProgressTracker tracker = new ProgressTracker(2);
        tracker.observe("iron:0");
        tracker.observe("iron:0");
        assertFalse(tracker.observe("iron:1"), "进度变了,重新数");
        assertTrue(tracker.observe("iron:1"));
    }

    @Test
    void resetClearsTheStreak() {
        ProgressTracker tracker = new ProgressTracker(2);
        assertFalse(tracker.observe("x"));
        assertTrue(tracker.observe("x"));
        tracker.reset();
        assertFalse(tracker.observe("x"), "reset 后重新数");
    }
}
