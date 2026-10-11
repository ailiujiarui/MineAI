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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). A minimal, world-agnostic
 * stand-in for baritone.utils.BlockStateInterface: it reads block states from
 * any BlockGetter and answers the loaded-chunk question through an optional
 * predicate.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads block states with vertical clamping, matching how Baritone treats
 * queries outside the world as air.
 *
 * @author leijurv
 */
public class BlockStateInterface {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public final BlockGetter access;
    public final BetterWorldBorder worldBorder;
    private final Chunks chunks;

    /** @return whether the chunk at {@code (chunkX, chunkZ)} is loaded. */
    public interface Chunks {
        boolean hasChunk(int chunkX, int chunkZ);
    }

    public BlockStateInterface(BlockGetter access, BetterWorldBorder worldBorder, Chunks chunks) {
        this.access = access;
        this.worldBorder = worldBorder;
        this.chunks = chunks;
    }

    public BlockStateInterface(BlockGetter access, BetterWorldBorder worldBorder) {
        this(access, worldBorder, null);
    }

    public BlockState get0(BlockPos pos) {
        return get0(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState get0(int x, int y, int z) {
        int minY = access.getMinBuildHeight();
        if (y < minY || y >= minY + access.getHeight()) {
            return AIR;
        }
        return access.getBlockState(new BlockPos(x, y, z));
    }

    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        return chunks == null || chunks.hasChunk(blockX >> 4, blockZ >> 4);
    }

    public boolean isLoaded(int x, int z) {
        return worldContainsLoadedChunk(x, z);
    }
}
