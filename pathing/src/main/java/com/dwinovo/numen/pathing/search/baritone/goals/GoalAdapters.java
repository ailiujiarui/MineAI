package com.dwinovo.numen.pathing.search.baritone.goals;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.baritone.Goal;

import net.minecraft.core.BlockPos;

/**
 * Turns Numen's own goals
 * ({@link com.dwinovo.numen.pathing.search.Goal}, built by
 * {@link com.dwinovo.numen.pathing.search.Goals}) into the vendored
 * {@code baritone.Goal} the ported A* accepts.
 *
 * <p>A position target is mapped straight onto the same-shaped Baritone goal:
 * a cell to {@link GoalBlock}, a column to {@link GoalXZ}, a level to
 * {@link GoalYLevel}. Those need no body, and their heuristics are the exact
 * octile/vertical lower bounds.
 *
 * <p>Every other Numen goal (dig, place, use, near, away, any/all) decides
 * arrival from {@code Goal.contains(x, y, z, stance)}, so the adapter needs a
 * {@link StanceSource} to answer Baritone's stance-free
 * {@code isInGoal(x, y, z)}. Its heuristic is the goal's own
 * {@code estimate}, which already follows the same per-block split.
 */
public final class GoalAdapters {

    private GoalAdapters() {}

    /**
     * Adapts a goal whose arrival test only looks at coordinates.
     *
     * @throws IllegalArgumentException if {@code goal} needs a body pose
     * @see #adapt(com.dwinovo.numen.pathing.search.Goal, StanceSource)
     */
    public static Goal adapt(com.dwinovo.numen.pathing.search.Goal goal) {
        Objects.requireNonNull(goal, "goal");
        if (goal instanceof Goals.Position position) {
            return position(position);
        }
        throw new IllegalArgumentException("目标 " + goal
                + " 的到达判定依赖身体姿态,adapt 需要 StanceSource:用 adapt(goal, stances)");
    }

    /**
     * Adapts any Numen goal. When it is not a plain position, the returned goal
     * asks {@code stances} how the body would stand at each node and forwards
     * to the goal's own reach test.
     */
    public static Goal adapt(com.dwinovo.numen.pathing.search.Goal goal, StanceSource stances) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(stances, "stances");
        if (goal instanceof Goals.Position position) {
            return position(position);
        }
        return new Adapted(goal, stances);
    }

    /** A Numen position target: cell, column, or level. */
    public static Goal position(Goals.Position position) {
        if (position.cell()) {
            return new GoalBlock(position.x(), position.y(), position.z());
        }
        if (position.x() != null) {
            return new GoalXZ(position.x(), position.z());
        }
        return new GoalYLevel(position.y());
    }

    /** Exactly one cell: Numen's {@code Goals.at}. */
    public static Goal block(BlockPos pos) {
        return new GoalBlock(pos);
    }

    /** That cell or the one below: useful for a dig or place target. */
    public static Goal twoBlocks(BlockPos pos) {
        return new GoalTwoBlocks(pos);
    }

    /** That column, any height: Numen's {@code Goals.column}. */
    public static Goal column(int x, int z) {
        return new GoalXZ(x, z);
    }

    /** That height, any x/z: Numen's {@code Goals.level}. */
    public static Goal level(int y) {
        return new GoalYLevel(y);
    }

    /** Within a straight-line range: the shape of Numen's {@code Goals.within}. */
    public static Goal near(BlockPos center, int range) {
        return new GoalNear(center, range);
    }

    /** Adjacent to a block: the shape of Numen's {@code Goals.dig}/{@code Goals.place}. */
    public static Goal getTo(BlockPos block) {
        return new GoalGetToBlock(block);
    }

    /** Any one of several: Numen's {@code Goals.anyOf}. */
    public static Goal any(Goal... goals) {
        return new GoalComposite(goals);
    }

    /** Away from every position: Numen's {@code Goals.awayFrom}. */
    public static Goal runAway(double distance, BlockPos... from) {
        return new GoalRunAway(distance, from);
    }

    private static final class Adapted implements Goal {

        private final com.dwinovo.numen.pathing.search.Goal goal;
        private final StanceSource stances;

        Adapted(com.dwinovo.numen.pathing.search.Goal goal, StanceSource stances) {
            this.goal = goal;
            this.stances = stances;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            Stance stance = stances.stance(x, y, z);
            return stance != null && goal.contains(x, y, z, stance);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return goal.estimate(x, y, z);
        }

        @Override
        public String toString() {
            return "Adapted(" + goal + ")";
        }
    }
}
