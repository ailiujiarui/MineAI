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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The subset of
 * baritone.api.utils.PathCalculationResult the A* core returns.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * The outcome of one path calculation: why it stopped and, if it found one, the
 * path.
 *
 * @author leijurv
 */
public record PathCalculationResult(Type type, IPath path) {

    public enum Type {
        /** A path was found that ends inside the goal. */
        SUCCESS_TO_GOAL,
        /** A path was found that advances toward the goal but does not reach it. */
        SUCCESS_SEGMENT,
        /** No path at all was found. */
        FAILURE,
        /** The calculation was cancelled. */
        CANCELLATION,
        /** The calculation threw an exception. */
        EXCEPTION
    }

    public PathCalculationResult(Type type) {
        this(type, null);
    }

    public boolean succeeded() {
        return type == Type.SUCCESS_TO_GOAL || type == Type.SUCCESS_SEGMENT;
    }
}
