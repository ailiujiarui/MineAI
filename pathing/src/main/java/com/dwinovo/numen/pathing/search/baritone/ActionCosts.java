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
 * Ported for Numen from Baritone 1.21.1 (LGPL-3.0). The single constant the
 * A* search core reads out of baritone.api.pathing.movement.ActionCosts.
 */
package com.dwinovo.numen.pathing.search.baritone;

/**
 * Movement cost constants. Only the sentinel for an impossible move is kept
 * here; the real costs live in Numen's own cost model.
 *
 * @author leijurv
 */
public final class ActionCosts {

    /**
     * The cost of a movement that cannot be performed. Any cost at or above
     * this is treated as infinity by the search.
     */
    public static final double COST_INF = 1000000;

    private ActionCosts() {}
}
