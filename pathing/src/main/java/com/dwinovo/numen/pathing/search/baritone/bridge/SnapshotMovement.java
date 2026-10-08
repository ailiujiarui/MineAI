package com.dwinovo.numen.pathing.search.baritone.bridge;

import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.search.SearchView;
import com.dwinovo.numen.pathing.search.baritone.MutableMoveResult;
import com.dwinovo.numen.pathing.search.baritone.movement.Moves;

/**
 * Prices a Numen step with Baritone's own movement cost calculators, reading
 * the locked {@link SearchView} snapshot through the ported
 * {@code movement.CalculationContext}.
 *
 * <p>Only the movements Baritone's model describes by a fixed offset are
 * mapped ({@code TRAVERSE_*}, {@code DIAGONAL_*}, {@code ASCEND_*},
 * {@code DESCEND_*}, {@code PARKOUR_*}, {@code PILLAR}, {@code DOWNWARD}).
 * A step the ported model does not describe, cannot perform, or lands
 * somewhere else than the Numen maneuver ({@code cost} returns
 * {@link Double#NaN}) is left to Numen's own cost model by the caller.
 *
 * <p>The snapshot is read, never the live world: the same bytes the search
 * was handed, and no {@code Recall} bookkeeping of this bridge is touched, so
 * a ported probe that reaches past the snapshot cannot turn an exhausted
 * search into an "unloaded" one.
 */
final class SnapshotMovement {

    private final com.dwinovo.numen.pathing.search.baritone.movement.CalculationContext context;
    private final MutableMoveResult result = new MutableMoveResult();

    SnapshotMovement(SearchView view) {
        this.context = new com.dwinovo.numen.pathing.search.baritone.movement.CalculationContext(view, view.border());
    }

    /**
     * @return Baritone's cost for that movement from the real cell, or
     *         {@link Double#NaN} when Baritone's movement model does not
     *         describe this step (no equivalent, impossible there, or a
     *         different landing).
     */
    double cost(MoveKind kind, Heading heading, int x, int y, int z, int toX, int toY, int toZ) {
        Moves move = map(kind, heading);
        if (move == null) {
            return Double.NaN;
        }
        result.reset();
        result.cost = com.dwinovo.numen.pathing.search.baritone.movement.ActionCosts.COST_INF;
        move.apply(context, x, y, z, result);
        if (!(result.cost < com.dwinovo.numen.pathing.search.baritone.movement.ActionCosts.COST_INF)) {
            return Double.NaN;
        }
        if (result.x != toX || result.y != toY || result.z != toZ) {
            return Double.NaN;
        }
        return result.cost;
    }

    private static Moves map(MoveKind kind, Heading h) {
        if (kind == MoveKind.WALK) {
            return cardinal(h, Moves.TRAVERSE_NORTH, Moves.TRAVERSE_SOUTH, Moves.TRAVERSE_EAST, Moves.TRAVERSE_WEST);
        }
        if (kind == MoveKind.ASCEND) {
            return cardinal(h, Moves.ASCEND_NORTH, Moves.ASCEND_SOUTH, Moves.ASCEND_EAST, Moves.ASCEND_WEST);
        }
        if (kind == MoveKind.DESCEND || kind == MoveKind.FALL) {
            return cardinal(h, Moves.DESCEND_NORTH, Moves.DESCEND_SOUTH, Moves.DESCEND_EAST, Moves.DESCEND_WEST);
        }
        if (kind == MoveKind.PARKOUR) {
            return cardinal(h, Moves.PARKOUR_NORTH, Moves.PARKOUR_SOUTH, Moves.PARKOUR_EAST, Moves.PARKOUR_WEST);
        }
        if (kind == MoveKind.DIAGONAL) {
            if (h.dy() != 0) {
                return null;
            }
            if (h.dx() > 0) {
                return h.dz() < 0 ? Moves.DIAGONAL_NORTHEAST : Moves.DIAGONAL_SOUTHEAST;
            }
            if (h.dx() < 0) {
                return h.dz() < 0 ? Moves.DIAGONAL_NORTHWEST : Moves.DIAGONAL_SOUTHWEST;
            }
            return null;
        }
        if (kind == MoveKind.PILLAR) {
            return Moves.PILLAR;
        }
        if (kind == MoveKind.DOWNWARD) {
            return Moves.DOWNWARD;
        }
        // CLIMB and SWIM have no fixed-offset Baritone movement: Baritone folds
        // them into the ladder branches of Pillar/Downward and the water branch
        // of Traverse, so they stay on Numen's own cost model.
        return null;
    }

    private static Moves cardinal(Heading h, Moves north, Moves south, Moves east, Moves west) {
        if (h.dz() < 0) {
            return north;
        }
        if (h.dz() > 0) {
            return south;
        }
        if (h.dx() > 0) {
            return east;
        }
        if (h.dx() < 0) {
            return west;
        }
        return null;
    }
}
