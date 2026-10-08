package com.dwinovo.numen.client.spectator;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SpectatorClientSessionTest {
    @Test void switchAndRebindRejectOldHudAndMenuGenerations() {
        var session = new SpectatorClientSession();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(session.update(1, first, true));
        assertTrue(session.update(2, second, true));
        assertFalse(session.accepts(1, first));
        assertFalse(session.update(1, first, true));
        assertFalse(session.update(1, first, false));
        assertFalse(session.update(2, first, true));
        assertTrue(session.update(3, second, true));
        assertFalse(session.accepts(2, second));
        assertTrue(session.accepts(3, second));
    }

    @Test void exitIsTerminalForItsGenerationButDisconnectAllowsAnotherServer() {
        var session = new SpectatorClientSession();
        UUID target = UUID.randomUUID();
        assertTrue(session.update(25, target, true));
        assertTrue(session.update(25, target, false));
        assertFalse(session.update(25, target, true));
        assertFalse(session.accepts(25, target));
        session.reset();
        assertTrue(session.update(1, target, true));
    }
}
