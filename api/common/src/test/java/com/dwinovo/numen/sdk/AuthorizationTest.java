package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.permission.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizationTest {
    @Test
    void unresolvedAndUncoveredAreNotDenied() {
        for (Verdict verdict : new Verdict[]{Verdict.pending("owner absent"), Verdict.uncovered()}) {
            ApiError error = assertThrows(ApiError.class, () -> Authorization.require(verdict, "ae2.craft.request()"));
            assertEquals(ErrorKind.NEEDS_CONSENT, error.kind());
        }
    }

    @Test
    void theGateOutcomeIsPreserved() {
        assertDoesNotThrow(() -> Authorization.require(Verdict.allow(), "ae2.craft.request()"));
        ApiError error = assertThrows(ApiError.class,
                () -> Authorization.require(Verdict.deny("observe mode"), "ae2.craft.request()"));
        assertEquals(ErrorKind.DENIED, error.kind());
        assertEquals("did not run ae2.craft.request(): observe mode", error.getMessage());
    }
}
