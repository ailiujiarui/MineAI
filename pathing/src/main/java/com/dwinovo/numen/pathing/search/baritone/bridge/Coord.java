package com.dwinovo.numen.pathing.search.baritone.bridge;

import net.minecraft.core.BlockPos;

/**
 * The vendored A* keys its nodes by three ints (the "block position"). Numen's
 * search nodes carry two extra pieces of state besides the real cell: how many
 * cells have been altered so far (only when an alteration budget is set) and
 * the breath band. To keep those apart as distinct nodes we fold the whole
 * state injectively into the three ints the vendored finder passes around:
 *
 * <ul>
 *   <li>{@code ex}/{@code ey} are the two halves of {@link BlockPos#asLong};
 *       together they recover the real cell;</li>
 *   <li>{@code st} packs the breath band (21 bits) and the altered-cell count
 *       (11 bits).</li>
 * </ul>
 *
 * The finder itself never reads the coordinates for anything but node identity
 * (world queries and offsets are neutralised in {@link BridgeSearch}), so the
 * mangling is invisible to it.
 */
final class Coord {

    private Coord() {}

    static long base(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    static long base(int ex, int ey) {
        return ((long) ex << 32) | (ey & 0xFFFFFFFFL);
    }

    static int ex(long base) {
        return (int) (base >>> 32);
    }

    static int ey(long base) {
        return (int) base;
    }

    static int x(long base) {
        return BlockPos.getX(base);
    }

    static int y(long base) {
        return BlockPos.getY(base);
    }

    static int z(long base) {
        return BlockPos.getZ(base);
    }

    static int st(int band, int used) {
        return (band & 0x1FFFFF) | (used << 21);
    }

    static int band(int st) {
        return st & 0x1FFFFF;
    }

    static int used(int st) {
        return (st >>> 21) & 0x7FF;
    }
}
