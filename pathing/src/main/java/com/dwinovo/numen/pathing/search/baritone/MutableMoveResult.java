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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). A minimal stand-in for
 * baritone.utils.pathing.MutableMoveResult: the destination and cost a move
 * writes when it is applied.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * The mutable result a {@link Move} writes when applied: where it ended up and
 * what it cost.
 *
 * @author leijurv
 */
public final class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;

    public void reset() {
        x = 0;
        y = 0;
        z = 0;
        cost = 0;
    }
}
