package com.dwinovo.numen.core.task.combat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackTaskRecordTest {

    @Test
    void anUnansweredConsentKeepsItsReasonWithoutClaimingARefusalOrLostTarget() {
        var record = new AttackTaskRecord("attack", "pending", 100, List.of(12), false);
        record.pending(12, "the owner is offline");

        assertEquals(Map.of(12, "the owner is offline"), record.pending());
        assertEquals("pending", record.status(12));
        assertFalse(record.terminal(12), "the target still exists and its later departure must be accounted for");
        assertTrue(record.refused().isEmpty());
        assertTrue(record.lost().isEmpty());
        assertEquals(0, record.strikes());
    }

    @Test
    void aLaterWorldOutcomeReplacesTheUnansweredConsent() {
        var record = new AttackTaskRecord("attack", "changes", 100, List.of(12, 13, 14, 15), false);
        for (int id : record.entityIds) {
            record.pending(id, "the owner is offline");
        }

        record.lost(12);
        record.defeated(13);
        record.unreachable(14);
        record.refused(15, "denied by rule");

        assertTrue(record.pending().isEmpty(), "the consent reason must not outlive what really happened");
        assertEquals("lost", record.status(12));
        assertEquals("defeated", record.status(13));
        assertEquals("unreachable", record.status(14));
        assertEquals("refused: denied by rule", record.status(15));
    }
}
