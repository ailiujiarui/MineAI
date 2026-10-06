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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). A minimal abstraction of
 * baritone.pathing.movement.CalculationContext, exposing only what the A*
 * search core reads: the world's height limits, chunk loading, the border,
 * and the set of available moves.
 */
package com.dwinovo.numen.pathing.search.baritone;

import java.util.List;

/**
 * Everything the A* search core needs to know about the world it is searching,
 * with no dependency on Minecraft.
 *
 * @author leijurv
 */
public interface CalculationContext {

    /** @return the lowest y a move may reach. */
    int minY();

    /** @return the world height above {@link #minY()}. */
    int height();

    /** @return whether the column {@code (x, z)} is loaded and safe to read. */
    boolean isLoaded(int x, int z);

    /** @return whether the column {@code (x, z)} lies entirely inside the world border. */
    boolean entirelyContains(int x, int z);

    /** @return every movement the search should consider. */
    List<Move> moves();
}
