package com.dwinovo.numen.client.spectator;

import java.util.UUID;

/** A closed generation cannot be reopened by a delayed state or menu packet. */
public final class SpectatorClientSession {
    private long generation;
    private UUID target;
    private boolean active;

    public boolean update(long generation, UUID target, boolean active) {
        if (generation <= 0 || generation < this.generation) return false;
        if (generation == this.generation
                && (!this.active || !target.equals(this.target))) return false;
        this.generation = generation;
        this.target = target;
        this.active = active;
        return true;
    }

    public boolean accepts(long generation, UUID target) {
        return active && this.generation == generation && this.target.equals(target);
    }

    public long generation() { return generation; }
    public UUID target() { return target; }
    public boolean active() { return active; }

    public void reset() {
        generation = 0;
        target = null;
        active = false;
    }
}
