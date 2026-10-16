package com.dwinovo.numen.pathing.search;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 局部前进交出哪一截,只在这里定。一段搜索没搜到这一段的终点({@link Search#advance})时,身体不干等:前进到边界上总价最小的节点
 * (LSS-LRTA* [Koenig &amp; Sun 2009]),而走到那里的路线里<b>只能停在最后一个回得了头的节点上</b>
 * ({@link com.dwinovo.numen.pathing.plan.Maneuver#reversible}):含着回不了头的一步(落差回不去的坠落、往下挖穿又爬不回来)就往回
 * 截到它之前。局部前进可能走进死路,只有停在退得回来的地方,走过去不对劲还能原路退回来。憋着气的节点也不当终点:身体停在水下
 * 等下一段,下一段接不上就困在那里。
 *
 * <p>搜到了这一段的终点的路线是完整的,里面的坠落、往下挖穿都允许——落点之后的路已经找到了,不截。
 */
public final class HandOver {

    /** 交出的一截,终点至少要离起点这么多格。 */
    static final int MIN_PARTIAL = 5;

    private HandOver() {}

    /** 一条路线交出的一截:前面回得了头的一段里,最后一个没憋着气的节点之前;离起点不够远为 null。 */
    static Route cut(Route route) {
        List<Route.Leg> legs = route.legs();
        int keep = 0;
        for (int i = 0; i < legs.size(); i++) {
            Route.Leg leg = legs.get(i);
            if (!leg.maneuver().reversible()) {
                break;
            }
            if (leg.air().held() <= 0) {
                keep = i + 1;
            }
        }
        if (keep == 0) {
            return null;
        }
        Route cut = keep == legs.size() ? route : new Route(route.start(), route.startStance(), legs.subList(0, keep));
        BlockPos from = cut.start();
        return cut.end().distSqr(from) > (double) MIN_PARTIAL * MIN_PARTIAL ? cut : null;
    }
}
