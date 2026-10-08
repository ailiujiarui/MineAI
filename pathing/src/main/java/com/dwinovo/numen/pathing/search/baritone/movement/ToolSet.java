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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). A player-free stand-in for
 * baritone.utils.ToolSet: with no inventory to inspect it mines with the bare
 * hand, which is exactly what the raw cost formulas need.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How fast the best available tool breaks a block. Numen's port has no player
 * inventory here, so this is the empty-hand speed.
 */
public class ToolSet {

    private final Map<Block, Double> breakStrengthCache = new HashMap<>();

    public ToolSet() {
    }

    /**
     * @param state the block state to be mined
     * @return 1/(time in ticks) using the bare hand
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(),
                block -> calculateSpeedVsBlock(ItemStack.EMPTY, block.defaultBlockState()));
    }

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return 0;
    }

    public boolean hasSilkTouch(ItemStack stack) {
        return false;
    }

    /**
     * Calculates how long it would take to mine the specified block with the
     * given item. A negative value means the block is unbreakable.
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }
}
