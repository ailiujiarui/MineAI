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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The strip of
 * baritone.utils.pathing.Favoring that AStarPathFinder reads: a per-node cost
 * multiplier keyed by the node's long hash.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * Discounts moves whose destination lies on a previously-known route, so a
 * replanning search sticks to the old road when another would be just as good.
 *
 * @author leijurv
 */
public interface Favoring {

    /** No route is favored; every factor is 1. */
    Favoring NONE = new Favoring() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public double calculate(long hashCode) {
            return 1;
        }
    };

    /** @return {@code true} if no node is favored. */
    boolean isEmpty();

    /** @return the multiplier applied to a move landing on the node with {@code hashCode}. */
    double calculate(long hashCode);
}
