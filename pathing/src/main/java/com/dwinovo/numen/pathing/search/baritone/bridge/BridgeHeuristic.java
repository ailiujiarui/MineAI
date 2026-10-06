package com.dwinovo.numen.pathing.search.baritone.bridge;

/**
 * The admissible estimate Numen's search uses for a real cell: the goal's own
 * estimate plus the burial floor. Kept as a plain function so the bridge does
 * not have to see the package-private {@code Burial}.
 */
@FunctionalInterface
public interface BridgeHeuristic {

    double at(int x, int y, int z);
}
