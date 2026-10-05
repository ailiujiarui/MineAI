package com.dwinovo.numen.spectator;

import java.util.List;

/** One action's real snapshots stay visible even when they arrive in one server tick. */
public final class MenuActionPlayback<T> {
    private static final long FRAME_MILLIS = 200;
    private List<T> frames = List.of();
    private int index;
    private boolean applied;
    private long nextAt;

    public void replace(List<T> incoming) {
        frames = List.copyOf(incoming);
        index = 0;
        applied = false;
        nextAt = 0;
    }

    /** A frame's clock starts only after its detached menu has actually received the snapshot. */
    public T frameToApply(long nowMillis) {
        if (!active()) return null;
        if (!applied) return frames.get(index);
        if (index + 1 < frames.size() && nowMillis >= nextAt) {
            index++;
            applied = false;
            return frames.get(index);
        }
        return null;
    }

    public void applied(long nowMillis) {
        if (!active() || applied) return;
        applied = true;
        nextAt = nowMillis + FRAME_MILLIS;
    }

    public boolean active() { return !frames.isEmpty(); }
    public boolean lastFrame() { return active() && index + 1 == frames.size(); }
    public boolean finished(long nowMillis) { return lastFrame() && applied && nowMillis >= nextAt; }

    public void clear() {
        frames = List.of();
        index = 0;
        applied = false;
        nextAt = 0;
    }
}
