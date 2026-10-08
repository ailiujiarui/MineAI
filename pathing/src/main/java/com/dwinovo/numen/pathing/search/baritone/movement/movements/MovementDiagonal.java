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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). Cost calculation only.
 */
package com.dwinovo.numen.pathing.search.baritone.movement.movements;

import java.util.HashSet;
import java.util.Set;

import com.dwinovo.numen.pathing.search.baritone.MutableMoveResult;
import com.dwinovo.numen.pathing.search.baritone.movement.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.movement.CalculationContext;
import com.dwinovo.numen.pathing.search.baritone.movement.Movement;
import com.dwinovo.numen.pathing.search.baritone.movement.MovementHelper;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

public class MovementDiagonal extends Movement {

    private static final double SQRT_2 = Math.sqrt(2);

    public MovementDiagonal(BetterBlockPos start, Direction dir1, Direction dir2, int dy) {
        this(start, start.relative(dir1), start.relative(dir2), dir2, dy);
    }

    private MovementDiagonal(BetterBlockPos start, BetterBlockPos dir1, BetterBlockPos dir2, Direction drr2, int dy) {
        this(start, dir1.relative(drr2).above(dy), dir1, dir2);
    }

    private MovementDiagonal(BetterBlockPos start, BetterBlockPos end, BetterBlockPos dir1, BetterBlockPos dir2) {
        super(start, end, new BetterBlockPos[]{dir1, dir1.above(), dir2, dir2.above(), end, end.above()});
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is incorrect
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        BetterBlockPos diagA = new BetterBlockPos(src.x, src.y, dest.z);
        BetterBlockPos diagB = new BetterBlockPos(dest.x, src.y, src.z);
        set.add(src);
        set.add(diagA);
        set.add(diagB);
        set.add(dest);
        if (dest.y < src.y) {
            set.add(dest.above());
            set.add(diagA.below());
            set.add(diagB.below());
        } else if (dest.y > src.y) {
            set.add(src.above());
            set.add(diagA.above());
            set.add(diagB.above());
        }
        return set;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        BlockState destIntoUpper = context.get(destX, y + 1, destZ);
        if (!MovementHelper.canWalkThrough(context, destX, y + 1, destZ, destIntoUpper)) {
            return;
        }
        BlockState destInto = context.get(destX, y, destZ);
        BlockState fromDown;
        boolean ascend = false;
        BlockState destWalkOn;
        boolean descend = false;
        boolean frostWalker = false;
        boolean sneaking = false;
        if (!MovementHelper.canWalkThrough(context, destX, y, destZ, destInto)) {
            ascend = true;
            if (!context.allowDiagonalAscend || !MovementHelper.canWalkThrough(context, x, y + 2, z) || !MovementHelper.canWalkOn(context, destX, y, destZ, destInto) || !MovementHelper.canWalkThrough(context, destX, y + 2, destZ)) {
                return;
            }
            destWalkOn = destInto;
            fromDown = context.get(x, y - 1, z);
        } else {
            destWalkOn = context.get(destX, y - 1, destZ);
            fromDown = context.get(x, y - 1, z);
            boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, fromDown);
            frostWalker = standingOnABlock && MovementHelper.canUseFrostWalker(context, destWalkOn);
            if (!frostWalker && !MovementHelper.canWalkOn(context, destX, y - 1, destZ, destWalkOn)) {
                descend = true;
                if (!context.allowDiagonalDescend || !MovementHelper.canWalkOn(context, destX, y - 2, destZ) || !MovementHelper.canWalkThrough(context, destX, y - 1, destZ, destWalkOn)) {
                    return;
                }
            }
            frostWalker &= !context.assumeWalkOnWater;
        }
        BlockState startState = context.get(x, y, z);
        if (isBlockingDoor(startState, x, z, destX, destZ) || isBlockingDoor(ascend ? destIntoUpper : destInto, destX, destZ, x, y)) {
            return; // Easier to just traverse instead
        }
        double multiplier = WALK_ONE_BLOCK_COST;
        // For either possible soul sand, that affects half of our walking
        if (destWalkOn.is(Blocks.SOUL_SAND)) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && destWalkOn.is(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        } else if (frostWalker) {
            // frostwalker lets us walk on water without the penalty
        } else if (destWalkOn.getBlock() == Blocks.WATER) {
            multiplier += context.walkOnWaterOnePenalty * SQRT_2;
        }
        Block fromDownBlock = fromDown.getBlock();
        if (MovementHelper.isClimbable(fromDownBlock)) {
            return;
        }
        if (fromDownBlock == Blocks.SOUL_SAND) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && fromDownBlock.equals(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        }
        BlockState cuttingOver1 = context.get(x, y - 1, destZ);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver1.is(Blocks.MAGMA_BLOCK)) || MovementHelper.isLava(cuttingOver1)) {
            return;
        }
        BlockState cuttingOver2 = context.get(destX, y - 1, z);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver2.is(Blocks.MAGMA_BLOCK)) || MovementHelper.isLava(cuttingOver2)) {
            return;
        }
        boolean water = false;
        Block startIn = startState.getBlock();
        if (MovementHelper.isWater(startState) || MovementHelper.isWater(destInto)) {
            if (ascend) {
                return;
            }
            multiplier = context.waterWalkSpeed;
            water = true;
        }
        BlockState pb0 = context.get(x, y, destZ);
        BlockState pb2 = context.get(destX, y, z);
        if (ascend) {
            boolean ATop = MovementHelper.canWalkThrough(context, x, y + 2, destZ);
            boolean AMid = MovementHelper.canWalkThrough(context, x, y + 1, destZ);
            boolean ALow = MovementHelper.canWalkThrough(context, x, y, destZ, pb0);
            boolean BTop = MovementHelper.canWalkThrough(context, destX, y + 2, z);
            boolean BMid = MovementHelper.canWalkThrough(context, destX, y + 1, z);
            boolean BLow = MovementHelper.canWalkThrough(context, destX, y, z, pb2);
            ALow &= !isBlockingDoor(pb0, x, destZ, destX, z);
            BLow &= !isBlockingDoor(pb2, destX, z, x, destZ);
            if ((!(ATop && AMid && ALow) && !(BTop && BMid && BLow)) // no option
                    || MovementHelper.avoidWalkingInto(pb0) // bad
                    || MovementHelper.avoidWalkingInto(pb2) // bad
                    || (ATop && AMid && MovementHelper.canWalkOn(context, x, y, destZ, pb0)) // we could just ascend
                    || (BTop && BMid && MovementHelper.canWalkOn(context, destX, y, z, pb2)) // we could just ascend
                    || (!ATop && AMid && ALow) // head bonk A
                    || (!BTop && BMid && BLow)) { // head bonk B
                return;
            }
            res.cost = multiplier * SQRT_2 + JUMP_ONE_BLOCK_COST;
            res.x = destX;
            res.z = destZ;
            res.y = y + 1;
            return;
        }
        double optionA = MovementHelper.getMiningDurationTicks(context, x, y, destZ, pb0, false);
        double optionB = MovementHelper.getMiningDurationTicks(context, destX, y, z, pb2, false);
        optionA += isBlockingDoor(pb0, x, destZ, destX, z) ? 1 : 0;
        optionB += isBlockingDoor(pb2, destX, z, x, destZ) ? 1 : 0;
        if (optionA != 0 && optionB != 0) {
            return;
        }
        BlockState pb1 = context.get(x, y + 1, destZ);
        optionA += MovementHelper.getMiningDurationTicks(context, x, y + 1, destZ, pb1, true);
        if (optionA != 0 && optionB != 0) {
            return;
        }
        BlockState pb3 = context.get(destX, y + 1, z);
        if (optionA == 0 && ((MovementHelper.avoidWalkingInto(pb2) && pb2.getBlock() != Blocks.WATER) || MovementHelper.avoidWalkingInto(pb3))) {
            return;
        }
        optionB += MovementHelper.getMiningDurationTicks(context, destX, y + 1, z, pb3, true);
        if (optionA != 0 && optionB != 0) {
            return;
        }
        if (optionB == 0 && ((MovementHelper.avoidWalkingInto(pb0) && pb0.getBlock() != Blocks.WATER) || MovementHelper.avoidWalkingInto(pb1))) {
            return;
        }
        if (optionA != 0 || optionB != 0) {
            multiplier *= SQRT_2 - 0.001;
            if (MovementHelper.isClimbable(startIn)) {
                return;
            }
        } else {
            if (context.canSprint && !water && !sneaking) {
                multiplier *= SPRINT_MULTIPLIER;
            }
        }
        res.cost = multiplier * SQRT_2;
        if (descend) {
            res.cost += Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
            res.y = y - 1;
        } else {
            res.y = y;
        }
        res.x = destX;
        res.z = destZ;
    }

    /**
     * We can always pass wooden doors in src or dest and we can also pass doors
     * in the A or B columns, unless their hinge points towards the other column.
     */
    private static boolean isBlockingDoor(BlockState state, int doorX, int doorZ, int otherX, int otherZ) {
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }

        Vec3i offset = state.getValue(HorizontalDirectionalBlock.FACING).getNormal();
        int ox = offset.getX();
        int oz = offset.getZ();

        int nbrX;
        int nbrZ;
        if (state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT) {
            nbrX = doorX - ox + oz;
            nbrZ = doorZ - oz - ox;
        } else {
            nbrX = doorX - ox - oz;
            nbrZ = doorZ - oz + ox;
        }

        return nbrX == otherX && nbrZ == otherZ;
    }
}
