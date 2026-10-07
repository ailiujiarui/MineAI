package com.dwinovo.numen.pathing.search.baritone.bridge;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;

/**
 * One Numen search node: the real cell plus the extra state Numen's search
 * carries (how many cells were altered, the breath band), and everything the
 * vendored finder does not model: the stance the body is in, how much air is
 * left, the step that got here, its cost and the world such as that step left
 * it. Instances live in the {@link SearchNode} chain hung off a single real
 * cell in {@link BridgeSearch}.
 */
final class SearchNode {

    final long base;
    final int st;
    final int x;
    final int y;
    final int z;
    final int used;
    final int band;
    final double h;

    double g = Double.POSITIVE_INFINITY;
    Stance stance;
    Breath.Air air;
    Maneuver via;
    double viaCost;
    boolean inGoal;
    /** What stopping here costs on top of {@link #g} (Numen's {@code Goal#arrival}); only meaningful when {@link #inGoal}. */
    double arrival;
    WorldView here;
    SearchNode parent;
    SearchNode next;

    SearchNode(long base, int st, int x, int y, int z, int used, int band, double h) {
        this.base = base;
        this.st = st;
        this.x = x;
        this.y = y;
        this.z = z;
        this.used = used;
        this.band = band;
        this.h = h;
    }
}
