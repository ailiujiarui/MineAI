package com.dwinovo.numen.spectator;

/** Ordering and close delay are independent of world ticks and survive no callbacks from an old menu. */
public final class MenuDisplayTimeline {
    private long menuId = -1;
    private long revision = -1;
    private long closeAt;
    private boolean closed;
    private boolean dismissed;

    public boolean accept(long incomingMenuId, long incomingRevision, boolean opening, boolean closing) {
        if (incomingMenuId < menuId || (incomingMenuId == menuId && incomingRevision <= revision)) return false;
        if (incomingMenuId > menuId) {
            if (!opening && !closing) return false;
            menuId = incomingMenuId;
            revision = -1;
            closed = false;
            dismissed = false;
            closeAt = 0;
        } else if (closed || dismissed) {
            return false;
        }
        revision = incomingRevision;
        if (closing) closed = true;
        return true;
    }

    public void applied(long nowMillis) {
        if (closed && closeAt == 0 && !dismissed) closeAt = nowMillis + 1000;
    }

    public boolean expired(long nowMillis) { return !dismissed && closeAt != 0 && nowMillis >= closeAt; }
    public void dismiss() { dismissed = true; closeAt = 0; }
    public boolean dismissed() { return dismissed; }
    public long menuId() { return menuId; }
    public void reset() { menuId = -1; revision = -1; closeAt = 0; closed = false; dismissed = false; }
}
