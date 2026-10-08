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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The subset of Baritone's
 * global settings the movement cost calculators read, with Baritone's defaults.
 * Numen has no settings file in this module yet, so the movement package holds
 * one shared instance; a caller may tweak its fields before building a context.
 */
package com.dwinovo.numen.pathing.search.baritone.movement;

import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The knobs the cost formulas depend on, defaulted exactly as Baritone does.
 */
public final class MovementSettings {

    public boolean allowBreak = true;
    public boolean allowSprint = true;
    public boolean allowPlace = true;
    public double placeBlockCost = 20D;

    public boolean allowParkour = false;
    public boolean allowParkourPlace = false;
    public boolean allowJumpAtBuildLimit = false;
    public boolean allowParkourAscend = true;

    public boolean assumeWalkOnWater = false;
    public boolean assumeWalkOnLava = false;

    public boolean allowDiagonalDescend = false;
    public boolean allowDiagonalAscend = false;
    public boolean allowDownward = true;

    public int minFallHeight = 3;
    public int maxFallHeightNoWater = 3;
    public int maxFallHeightBucket = 20;

    public double blockBreakAdditionalCost = 2D;
    public double backtrackCostFavoringCoefficient = 0.5D;
    public double jumpPenalty = 2D;
    public double walkOnWaterOnePenalty = 3D;

    public boolean allowWalkOnMagmaBlocks = false;
    public boolean allowVines = false;
    public boolean allowWalkOnBottomSlab = true;
    public boolean avoidUpdatingFallingBlocks = true;
    public boolean strictLiquidCheck = false;

    public List<Block> allowBreakAnyway = List.of();
    public List<Block> blocksToAvoid = List.of(Blocks.TRIPWIRE);
    public List<Block> blocksToDisallowBreaking = List.of();

    public MovementSettings() {
    }

    public MovementSettings copy() {
        MovementSettings copy = new MovementSettings();
        copy.allowBreak = allowBreak;
        copy.allowSprint = allowSprint;
        copy.allowPlace = allowPlace;
        copy.placeBlockCost = placeBlockCost;
        copy.allowParkour = allowParkour;
        copy.allowParkourPlace = allowParkourPlace;
        copy.allowJumpAtBuildLimit = allowJumpAtBuildLimit;
        copy.allowParkourAscend = allowParkourAscend;
        copy.assumeWalkOnWater = assumeWalkOnWater;
        copy.assumeWalkOnLava = assumeWalkOnLava;
        copy.allowDiagonalDescend = allowDiagonalDescend;
        copy.allowDiagonalAscend = allowDiagonalAscend;
        copy.allowDownward = allowDownward;
        copy.minFallHeight = minFallHeight;
        copy.maxFallHeightNoWater = maxFallHeightNoWater;
        copy.maxFallHeightBucket = maxFallHeightBucket;
        copy.blockBreakAdditionalCost = blockBreakAdditionalCost;
        copy.backtrackCostFavoringCoefficient = backtrackCostFavoringCoefficient;
        copy.jumpPenalty = jumpPenalty;
        copy.walkOnWaterOnePenalty = walkOnWaterOnePenalty;
        copy.allowWalkOnMagmaBlocks = allowWalkOnMagmaBlocks;
        copy.allowVines = allowVines;
        copy.allowWalkOnBottomSlab = allowWalkOnBottomSlab;
        copy.avoidUpdatingFallingBlocks = avoidUpdatingFallingBlocks;
        copy.strictLiquidCheck = strictLiquidCheck;
        copy.allowBreakAnyway = allowBreakAnyway;
        copy.blocksToAvoid = blocksToAvoid;
        copy.blocksToDisallowBreaking = blocksToDisallowBreaking;
        return copy;
    }
}
