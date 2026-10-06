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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). Baritone's movement
 * CalculationContext takes an IBaritone and reads player state; this port takes
 * a plain BlockGetter plus a WorldBorder, and fills the player-derived fields
 * from one shared MovementSettings. Every cost-relevant field and formula is
 * kept.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import static com.dwinovo.numen.pathing.search.baritone.movement.ActionCosts.COST_INF;

/**
 * Everything a movement needs to know about the world and the body.
 *
 * @author Brady
 */
public class CalculationContext {

    public final BlockGetter world;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost;
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;

    public final PrecomputedData precomputedData;

    public CalculationContext(BlockGetter world, WorldBorder border) {
        MovementSettings s = MovementHelper.SETTINGS;
        this.world = world;
        this.worldBorder = new BetterWorldBorder(border);
        this.bsi = new BlockStateInterface(world, worldBorder);
        this.toolSet = new ToolSet();
        this.hasThrowaway = s.allowPlace;
        this.hasWaterBucket = false;
        this.canSprint = s.allowSprint;
        this.placeBlockCost = s.placeBlockCost;
        this.allowBreak = s.allowBreak;
        this.allowBreakAnyway = s.allowBreakAnyway;
        this.allowParkour = s.allowParkour;
        this.allowParkourPlace = s.allowParkourPlace;
        this.allowJumpAtBuildLimit = s.allowJumpAtBuildLimit;
        this.allowParkourAscend = s.allowParkourAscend;
        this.assumeWalkOnWater = s.assumeWalkOnWater;
        this.allowFallIntoLava = false;
        this.frostWalker = 0;
        this.allowDiagonalDescend = s.allowDiagonalDescend;
        this.allowDiagonalAscend = s.allowDiagonalAscend;
        this.allowDownward = s.allowDownward;
        this.minFallHeight = s.minFallHeight;
        this.maxFallHeightNoWater = s.maxFallHeightNoWater;
        this.maxFallHeightBucket = s.maxFallHeightBucket;
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST;
        this.breakBlockAdditionalCost = s.blockBreakAdditionalCost;
        this.backtrackCostFavoringCoefficient = s.backtrackCostFavoringCoefficient;
        this.jumpPenalty = s.jumpPenalty;
        this.walkOnWaterOnePenalty = s.walkOnWaterOnePenalty;
        this.allowWalkOnMagmaBlocks = s.allowWalkOnMagmaBlocks;
        this.precomputedData = new PrecomputedData();
    }

    public CalculationContext(BlockGetter world) {
        this(world, new WorldBorder());
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    public double placeBucketCost() {
        return placeBlockCost;
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        return false;
    }
}
