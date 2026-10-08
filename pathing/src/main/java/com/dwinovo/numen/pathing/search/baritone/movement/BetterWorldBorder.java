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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0).
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import net.minecraft.world.level.border.WorldBorder;

/**
 * A cheap snapshot of a {@link WorldBorder} that answers the only two questions
 * the movement calculators ask of it.
 */
public class BetterWorldBorder {

    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    public BetterWorldBorder(WorldBorder border) {
        this.minX = border.getMinX();
        this.maxX = border.getMaxX();
        this.minZ = border.getMinZ();
        this.maxZ = border.getMaxZ();
    }

    public boolean entirelyContains(int x, int z) {
        return x + 1 > minX && x < maxX && z + 1 > minZ && z < maxZ;
    }

    public boolean canPlaceAt(int x, int z) {
        // Move in 1 block on all sides because we can't place a block at the very
        // edge against a block outside the border.
        return x > minX && x + 1 < maxX && z > minZ && z + 1 < maxZ;
    }
}
