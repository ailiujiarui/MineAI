package com.dwinovo.numen.client.spectator;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SpectatorCameraRestoreTest {
    @Test void exitBeforeOriginalDimensionTrackingWaitsForTheExactEntity() {
        var restore = new SpectatorCameraRestore();
        UUID original = UUID.randomUUID();
        restore.request(original, 41, "minecraft:overworld");
        assertFalse(restore.resolve(41, original, "minecraft:the_nether"));
        assertFalse(restore.resolve(41, UUID.randomUUID(), "minecraft:overworld"));
        assertFalse(restore.resolve(99, original, "minecraft:overworld"));
        assertTrue(restore.pending());
        assertTrue(restore.resolve(41, original, "minecraft:overworld"));
        assertFalse(restore.pending());
        assertFalse(restore.resolve(41, original, "minecraft:overworld"));
    }

    @Test void confirmedNewViewOrDisconnectClearsTheOldCameraBeforeAnotherWorldCanTrackIt() {
        var restore = new SpectatorCameraRestore();
        UUID original = UUID.randomUUID(), next = UUID.randomUUID();
        restore.request(original, 41, "minecraft:overworld");
        assertEquals(original, restore.beginViewing());
        assertFalse(restore.resolve(41, original, "minecraft:overworld"));
        restore.request(next, 99, "minecraft:the_end");
        assertFalse(restore.resolve(41, original, "minecraft:overworld"));
        assertTrue(restore.resolve(99, next, "minecraft:the_end"));
        restore.request(original, 41, "minecraft:overworld");
        restore.clear();
        assertFalse(restore.resolve(41, original, "minecraft:overworld"));
    }

    @Test void enterSentBeforeExitAckRetainsTheCameraWhenTheNewActiveAckArrives() {
        var session = new SpectatorClientSession();
        var restore = new SpectatorCameraRestore();
        UUID original = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(session.update(1, first, true));
        // Sending EXIT and then ENTER does not change the client session before either reply.
        assertTrue(session.active());
        assertTrue(session.update(2, first, false));
        restore.request(original, 41, "minecraft:overworld");
        assertTrue(session.update(3, second, true));
        UUID savedForNewView = restore.beginViewing();
        assertEquals(original, savedForNewView);
        assertFalse(restore.resolve(41, original, "minecraft:overworld"));
        assertTrue(session.update(4, second, false));
        restore.request(savedForNewView, 41, "minecraft:overworld");
        assertTrue(restore.resolve(41, original, "minecraft:overworld"));
    }

    @Test void rejectedOrStaleEnterDoesNotCancelAnExitRestoration() {
        var session = new SpectatorClientSession();
        var restore = new SpectatorCameraRestore();
        UUID original = UUID.randomUUID(), companion = UUID.randomUUID();
        assertTrue(session.update(2, companion, false));
        restore.request(original, 41, "minecraft:overworld");
        // A server-rejected request has no active reply; an old active reply is rejected as well.
        if (session.update(1, companion, true)) restore.beginViewing();
        assertTrue(restore.pending());
        assertTrue(restore.resolve(41, original, "minecraft:overworld"));
    }
}
