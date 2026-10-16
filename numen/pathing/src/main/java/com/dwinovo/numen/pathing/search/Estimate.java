package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索的估价:节点走到目标至少还要多少刻。全模块只在这里把四样加起来,每一样都是下界,所以估价不高过任何一条路线的真实代价:
 * <ul>
 *   <li>疾跑底价:水平距离乘疾跑一格({@link Goal#estimate});</li>
 *   <li>挖开的额外代价:路上绕不开要挖掉的格至少多少钱({@link Burial});</li>
 *   <li>水里的额外代价:路上绕不开要泡在水里走的格至少多花多少({@link Waters});</li>
 *   <li>升降的额外代价:要升降几个节点,每个至少比水平底价多花多少({@link Vertical})。</li>
 * </ul>
 * 每一样都在开搜时按世界算一次,之后每个节点只查表加乘法。
 */
final class Estimate {

    private final Goal goal;
    private final Kinematics.Pace pace;
    private final Burial burial;
    private final Vertical vertical;
    private final Waters waters;

    private Estimate(Goal goal, Kinematics.Pace pace, Burial burial, Vertical vertical, Waters waters) {
        this.goal = goal;
        this.pace = pace;
        this.burial = burial;
        this.vertical = vertical;
        this.waters = waters;
    }

    /** 这次搜索的估价:在 {@code view} 上按 {@code model} 的身体与规格,从 {@code start} 往 {@code goal};埋深最多定下 {@code cap} 格。 */
    static Estimate of(SearchView view, CostModel model, Goal goal, BlockPos start, int cap) {
        return new Estimate(goal, Kinematics.pace(model.body().stats()), Burial.of(view, model, goal, start, cap),
                Vertical.of(view, model, goal), Waters.of(view, model, goal, start, cap));
    }

    /** 脚在节点 {@code (x, y, z)} 的身体走到目标至少还要多少刻。 */
    double at(int x, int y, int z) {
        return goal.estimate(x, y, z, pace) + burial.floor(x, y, z) + vertical.floor(y) + waters.floor(x, y, z);
    }

    double at(BlockPos node) {
        return at(node.getX(), node.getY(), node.getZ());
    }
}
