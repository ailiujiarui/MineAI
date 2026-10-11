/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). Unlike the stripped
 * stand-in in the vendored search package, this one extends BlockPos and
 * carries the offset helpers the movement calculators use.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;

/**
 * A {@link BlockPos} with a lower-collision hash and inline offsets.
 *
 * @author leijurv
 */
public final class BetterBlockPos extends BlockPos {

    public static final BetterBlockPos ORIGIN = new BetterBlockPos(0, 0, 0);

    public final int x;
    public final int y;
    public final int z;

    public BetterBlockPos(int x, int y, int z) {
        super(x, y, z);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BetterBlockPos(double x, double y, double z) {
        this(Mth.floor(x), Mth.floor(y), Mth.floor(z));
    }

    public BetterBlockPos(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long longHash(BetterBlockPos pos) {
        return longHash(pos.x, pos.y, pos.z);
    }

    public static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof BetterBlockPos oth) {
            return oth.x == x && oth.y == y && oth.z == z;
        }
        if (o instanceof BlockPos oth) {
            return oth.getX() == x && oth.getY() == y && oth.getZ() == z;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (int) longHash(x, y, z);
    }

    @Override
    public BetterBlockPos above() {
        return new BetterBlockPos(x, y + 1, z);
    }

    @Override
    public BetterBlockPos above(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x, y + amt, z);
    }

    @Override
    public BetterBlockPos below() {
        return new BetterBlockPos(x, y - 1, z);
    }

    @Override
    public BetterBlockPos below(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x, y - amt, z);
    }

    @Override
    public BetterBlockPos relative(Direction dir) {
        Vec3i vec = dir.getNormal();
        return new BetterBlockPos(x + vec.getX(), y + vec.getY(), z + vec.getZ());
    }

    @Override
    public BetterBlockPos relative(Direction dir, int dist) {
        if (dist == 0) {
            return this;
        }
        Vec3i vec = dir.getNormal();
        return new BetterBlockPos(x + vec.getX() * dist, y + vec.getY() * dist, z + vec.getZ() * dist);
    }

    @Override
    public BetterBlockPos north() {
        return new BetterBlockPos(x, y, z - 1);
    }

    @Override
    public BetterBlockPos north(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x, y, z - amt);
    }

    @Override
    public BetterBlockPos south() {
        return new BetterBlockPos(x, y, z + 1);
    }

    @Override
    public BetterBlockPos south(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x, y, z + amt);
    }

    @Override
    public BetterBlockPos east() {
        return new BetterBlockPos(x + 1, y, z);
    }

    @Override
    public BetterBlockPos east(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x + amt, y, z);
    }

    @Override
    public BetterBlockPos west() {
        return new BetterBlockPos(x - 1, y, z);
    }

    @Override
    public BetterBlockPos west(int amt) {
        return amt == 0 ? this : new BetterBlockPos(x - amt, y, z);
    }

    public BetterBlockPos subtract(BetterBlockPos other) {
        return new BetterBlockPos(x - other.x, y - other.y, z - other.z);
    }

    public BetterBlockPos offset(BetterBlockPos other) {
        return new BetterBlockPos(x + other.x, y + other.y, z + other.z);
    }

    public double distanceSq(BetterBlockPos to) {
        double dx = (double) this.x - to.x;
        double dy = (double) this.y - to.y;
        double dz = (double) this.z - to.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(BetterBlockPos to) {
        return Math.sqrt(distanceSq(to));
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
