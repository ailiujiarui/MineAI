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

import java.util.stream.Stream;

import net.minecraft.util.Mth;

/**
 * One of the eight input combinations a move can use to steer, with the
 * resulting motion, so the closest to the ideal heading can be picked.
 */
public record MovementOption(Input input1, Input input2, float motionX, float motionZ) {

    private static final float SPRINT_MULTIPLIER = 1.3f;

    public MovementOption(Input input1, float motionX, float motionZ) {
        this(input1, null, motionX, motionZ);
    }

    public void setInputs(MovementState movementState) {
        if (input1 != null) {
            movementState.setInput(input1, true);
        }
        if (input2 != null) {
            movementState.setInput(input2, true);
        }
    }

    public float distanceToSq(float otherX, float otherZ) {
        return Mth.abs(motionX() - otherX) + Mth.abs(motionZ() - otherZ);
    }

    public static Stream<MovementOption> getOptions(float motionX, float motionZ, boolean canSprint) {
        float sx = canSprint ? motionX * SPRINT_MULTIPLIER : motionX;
        float sz = canSprint ? motionZ * SPRINT_MULTIPLIER : motionZ;
        return Stream.of(
                new MovementOption(Input.MOVE_FORWARD, sx, sz),
                new MovementOption(Input.MOVE_BACK, -motionX, -motionZ),
                new MovementOption(Input.MOVE_LEFT, -motionZ, motionX),
                new MovementOption(Input.MOVE_RIGHT, motionZ, -motionX),
                new MovementOption(Input.MOVE_FORWARD, Input.MOVE_LEFT, sx - motionZ, sz + motionX),
                new MovementOption(Input.MOVE_FORWARD, Input.MOVE_RIGHT, sx + motionZ, sz - motionX),
                new MovementOption(Input.MOVE_BACK, Input.MOVE_LEFT, -motionX - motionZ, -motionZ + motionX),
                new MovementOption(Input.MOVE_BACK, Input.MOVE_RIGHT, -motionX + motionZ, -motionZ - motionX)
        );
    }
}
