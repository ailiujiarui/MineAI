package com.dwinovo.numen.agent.recover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassifierTest {

    @Test
    void safetyWinsWhenSeveralFlagsAreSet() {
        assertEquals(FailureKind.THREATENED,
                FailureClassifier.classify(FailureSignal.of(FailureKind.UNREACHABLE, FailureKind.THREATENED)));
        assertEquals(FailureKind.INVENTORY_FULL,
                FailureClassifier.classify(FailureSignal.of(FailureKind.NO_TOOL, FailureKind.INVENTORY_FULL)));
    }

    @Test
    void aSingleFlagClassifiesDirectly() {
        assertEquals(FailureKind.UNREACHABLE, FailureClassifier.classify(FailureSignal.of(FailureKind.UNREACHABLE)));
        assertEquals(FailureKind.NO_TOOL, FailureClassifier.classify(FailureSignal.of(FailureKind.NO_TOOL)));
    }

    @Test
    void noFlagAndNoReasonIsNoFailure() {
        assertEquals(FailureKind.NONE, FailureClassifier.classify(FailureSignal.EMPTY));
        assertEquals(FailureKind.NONE, FailureClassifier.classify(null));
    }

    @Test
    void aReasonWithNoFlagIsUnknownNotGuessed() {
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.classify(FailureSignal.of("something odd happened")));
    }

    @Test
    void policyMapsEachKindToARecovery() {
        assertEquals(Recovery.ACQUIRE_TOOL, RecoveryPolicy.choose(FailureKind.NO_TOOL));
        assertEquals(Recovery.APPROACH, RecoveryPolicy.choose(FailureKind.UNREACHABLE));
        assertEquals(Recovery.RETARGET, RecoveryPolicy.choose(FailureKind.TARGET_GONE));
        assertEquals(Recovery.REPLAN, RecoveryPolicy.choose(FailureKind.INVENTORY_FULL));
        assertEquals(Recovery.REPLAN, RecoveryPolicy.choose(FailureKind.STALLED));
        assertEquals(Recovery.ASK, RecoveryPolicy.choose(FailureKind.UNKNOWN_ITEM));
        assertEquals(Recovery.ABORT, RecoveryPolicy.choose(FailureKind.THREATENED));
        assertEquals(Recovery.RETRY, RecoveryPolicy.choose(FailureKind.NONE));
    }
}
