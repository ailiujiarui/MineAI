package com.dwinovo.numen.pathing.search.baritone.bridge;

/**
 * The "at least this much digging remains" floor the search adds to the goal
 * estimate. The bridge cannot see the package-private {@code search.Burial},
 * so {@link BridgeSearch} takes it as this plain function and adds it to the
 * ported goal's own heuristic.
 */
@FunctionalInterface
public interface BurialFloor {

    /** @return the digging floor at the real cell {@code (x, y, z)}. */
    double at(int x, int y, int z);
}
