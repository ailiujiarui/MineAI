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

/**
 * A three-valued answer: the block-state class is always one of these, but the
 * position-dependent follow-up may still be needed for {@link #MAYBE}.
 *
 * @author leijurv
 */
public enum Ternary {
    YES, MAYBE, NO
}
