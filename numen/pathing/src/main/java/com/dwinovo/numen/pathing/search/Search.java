package com.dwinovo.numen.pathing.search;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索要的全部输入:读哪份世界、按哪份成本模型、从哪个节点出发、去哪、最多展开几个节点、沿不沿旧路,以及一段一段走时的三样:
 * 这一段走到哪算完、起点是接在哪一步后面、这一趟的记忆。
 *
 * @param start   起点节点:从身体脚下搜时由 {@link Origin} 从身体的真实位置定出,接着一条路线往下搜时是那条路线的终点
 * @param budget  最多展开几个节点(贪心与加权两遍合起来);结论不随机器快慢和 tick 速率变
 * @param advance 一段走到哪算完:到了目标,或到了一个估价比起点低 {@code advance} 刻的节点({@link AStar});无穷大是只认目标。
 *                估价已经是每种目标统一的"还差多少",段就用它量,不另造"离终点多远"
 * @param memory  这一趟的记忆冻下来的一份({@link TripMemory#frozen}):估价里抬高过的增量,和不许再走的步
 * @param arrival 走到起点的那一步:接着一条路线的终点往下搜时,身体到那里时怎么待着、那一步做过哪些改动,都照它算——
 *                快照里还没有那一步垫下的块;从身体脚下搜为 null
 * @param air     身体在起点时憋气的样子:从脚下搜是身体此刻的({@link Breath#now}),接着一条路线往下搜是走完那一步时的
 */
public record Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring,
                     double advance, TripMemory.Frozen memory, Maneuver arrival, Breath.Air air) {

    public Search {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(favoring, "favoring");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(air, "air");
        start = start.immutable();
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
        if (!(advance > 0)) {
            throw new IllegalArgumentException("一段要推进的估价要是正数:" + advance);
        }
        if (arrival != null && !arrival.to().equals(start)) {
            throw new IllegalArgumentException("起点 " + start + " 不是那一步的落点 " + arrival.to());
        }
    }

    /** 只认目标、从身体脚下出发的一次搜索:憋气从身体此刻的样子起,没有记忆。 */
    public Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring) {
        this(view, model, start, goal, budget, favoring, Double.POSITIVE_INFINITY, TripMemory.Frozen.NONE, null,
                model.body().breath().now());
    }

    /** 同一次搜索,走到估价比起点低 {@code ticks} 刻的节点就算这一段完了。 */
    public Search advancing(double ticks) {
        return new Search(view, model, start, goal, budget, favoring, ticks, memory, arrival, air);
    }

    /** 同一次搜索,带着这一趟的记忆。 */
    public Search remembering(TripMemory.Frozen memory) {
        return new Search(view, model, start, goal, budget, favoring, advance, memory, arrival, air);
    }

    /** 同一次搜索,起点是 {@code step} 这一步的落点:接着一条路线往下搜,憋气从走完这一步时的样子起。 */
    public Search after(Route.Leg step) {
        return step == null ? this
                : new Search(view, model, start, goal, budget, favoring, advance, memory, step.maneuver(), step.air());
    }

    /** 同一次搜索,憋气从 {@code from} 起(诊断问"憋得住的话有没有路"时是 {@link Breath#UNLIMITED})。 */
    public Search breathing(Breath.Air from) {
        return new Search(view, model, start, goal, budget, favoring, advance, memory, arrival, from);
    }

    /**
     * 同一份快照、起点与目标,换一份成本模型再搜(诊断问"换一种条件有没有路"时):不沿旧路,也不带这一趟的记忆——抬高过的估价
     * 是对着原来的规格算的,放宽之后真实代价更低,它不再是下界。
     */
    public Search under(CostModel other) {
        return new Search(view, other, start, goal, budget, Favoring.NONE, advance, TripMemory.Frozen.NONE, arrival, air);
    }
}
