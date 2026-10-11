package com.dwinovo.numen.client.spectator;

import java.util.UUID;

/** An exit camera packet can precede its entity's first tracking packet in the restored dimension. */
public final class SpectatorCameraRestore {
    private UUID target;
    private int entityId;
    private String dimension;

    public void request(UUID target, int entityId, String dimension) {
        this.target = target;
        this.entityId = entityId;
        this.dimension = dimension;
    }

    public boolean pending() { return target != null; }
    public UUID target() { return target; }
    public int entityId() { return entityId; }

    /** A confirmed new view retains the original identity and cancels the old tracking callback. */
    public UUID beginViewing() {
        UUID original = target;
        clear();
        return original;
    }

    public boolean resolve(int entityId, UUID target, String dimension) {
        if (!pending() || this.entityId != entityId || !this.target.equals(target)
                || !this.dimension.equals(dimension)) return false;
        clear();
        return true;
    }

    public void clear() {
        target = null;
        entityId = -1;
        dimension = null;
    }
}
