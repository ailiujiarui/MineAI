package com.dwinovo.numen.pathing.search.baritone.goals;

import com.dwinovo.numen.pathing.plan.Stance;

/**
 * Tells an adapter how the body would stand at a node, which Numen's goal
 * reach test needs and Baritone's {@code Goal.isInGoal(int, int, int)} does
 * not carry. A search feeding the vendored A* supplies the same lookup the
 * search uses for its own nodes.
 */
@FunctionalInterface
public interface StanceSource {

    /** @return how the body would stand at {@code (x, y, z)}, or null if it cannot. */
    Stance stance(int x, int y, int z);
}
