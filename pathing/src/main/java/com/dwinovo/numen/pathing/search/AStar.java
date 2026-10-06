package com.dwinovo.numen.pathing.search;

import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.baritone.bridge.BridgeHeuristic;
import com.dwinovo.numen.pathing.search.baritone.bridge.BridgeSearch;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;

/**
 * A*:从起点逐个展开 {@link com.dwinovo.numen.pathing.plan.Moves#ALL} 的每种走法,前提成立的步子按它的代价松弛。
 *
 * <p>这一层只做 Numen 语义的装配:算出身体在起点上的姿态、这次搜索的埋深与折价函数,然后交给
 * {@link BridgeSearch}——它把那套语义翻译成 step-1 收下的 vendored Baritone A*({@code AStarPathFinder})读得懂的
 * {@code CalculationContext}/{@code Goal}/{@code Move},跑完再翻回 {@link SearchResult}/{@link Route}。
 * 目标、成本模型、憋气、改动预算、旧路打折与半程路线的选取,结论仍由 Numen 的语义决定。
 */
public final class AStar {

    /** 半程路线的终点至少要离起点这么多格才交出。 */
    static final int MIN_PARTIAL = 5;

    private AStar() {}

    /** 在当前线程上跑完一次搜索;{@code cancelled} 在每展开一个节点前问一次。 */
    public static SearchResult run(Search search, BooleanSupplier cancelled) {
        SearchView view = search.view();
        Goal goal = search.goal();
        BlockPos startPos = search.start();
        Maneuver arrival = search.arrival();
        CostModel model = Goal.guarded(goal, search.model());
        BodyStats body = model.body().stats();
        Breath breath = model.body().breath();
        Stance startStance = arrival != null ? arrival.landing() : Stance.at(view, body, startPos);
        if (startStance == null) {
            return new SearchResult(SearchResult.Stop.STRANDED, null, 0, false);
        }
        Burial burial = Burial.of(view, model, goal, startPos, search.budget());
        BridgeHeuristic heuristic = (x, y, z) -> goal.estimate(x, y, z) + burial.floor(x, y, z);
        ToDoubleFunction<BlockPos> favoring = to -> search.favoring().factor(to);
        return new BridgeSearch(search, model, body, breath, startStance, heuristic, favoring).run(cancelled);
    }
}
