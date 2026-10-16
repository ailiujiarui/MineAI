package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;

/**
 * 一段多大、一趟愿意付多少:段长、一段的搜索预算、一趟的总预算,和这一趟已经花了多少。
 *
 * <p>一趟的收场只有一个判据:总预算(按展开的节点数)用完还没到。预算与起点到终点的估价成正比——远的路要走的段多——常数是
 * "按估价走完要几段、每段最多搜 {@link #SEGMENT_NODES} 个节点、再留 {@link #SLACK} 倍的余量给绕路与走不通的重搜"。真实世界有限但很大,
 * LRTA* 的"保证到达"要配一个总预算才有意义 [Korf 1990];它是性能约束,不是认输的经验阈值。
 */
final class Allowance {

    /** 一段的长度:这一段的终点,估价比起点低 {@value} 格疾跑的耗时。 */
    static final int SEGMENT_BLOCKS = 32;
    /** 一段的搜索最多展开几个节点(贪心与加权两遍合起来)。 */
    static final int SEGMENT_NODES = 4_000;
    /** 总预算是"按估价走完所有段、每段搜满"的几倍。 */
    static final int SLACK = 3;
    /**
     * 总预算至少够搜几段:估价是下界,真实要走的路常比它远得多(峡谷底、盆地、石头里),近处的估价小,"按估价要几段"就一两段,
     * 一两段的预算连一次绕路的重搜都撑不住。
     */
    static final int MIN_SEGMENTS = 10;
    /** 一段的快照读多大:以起点所在区块为中心的半径(区块数)。一段的终点在 {@value #SEGMENT_BLOCKS} 格以内,再留两圈给绕路。 */
    static final int SNAPSHOT_RADIUS = 4;

    private final int fixed;
    private int total;
    private int spent;

    /** @param fixed 调用方指定的总预算;0 是按估价定 */
    Allowance(int fixed) {
        if (fixed < 0) {
            throw new IllegalArgumentException("总预算不能为负:" + fixed);
        }
        this.fixed = fixed;
        this.total = fixed;
    }

    /** 一段要推进多少估价(刻):{@link #SEGMENT_BLOCKS} 格疾跑的耗时。 */
    static double advance(BodyStats body) {
        return SEGMENT_BLOCKS * Kinematics.pace(body).sprint();
    }

    /** 这一趟从估价 {@code estimate} 的地方起头:总预算按它定(调用方指定了的不动);已经定下的不再改。 */
    void begun(double estimate, double advance) {
        if (total == 0) {
            double segments = Math.max(MIN_SEGMENTS, Math.ceil(estimate / advance) * SLACK);
            total = (int) Math.min(Integer.MAX_VALUE, segments * SEGMENT_NODES);
        }
    }

    /** 下一段的搜索最多展开几个节点:一段的预算,和总预算还剩的,取小的。 */
    int segment() {
        return total == 0 ? SEGMENT_NODES : Math.max(1, Math.min(SEGMENT_NODES, total - spent));
    }

    /** 一次搜索展开了 {@code nodes} 个节点;至少记一个,一次搜索再没有进展也要花预算。 */
    void spend(int nodes) {
        spent += Math.max(1, nodes);
    }

    /** 总预算用完了。 */
    boolean spent() {
        return total > 0 && spent >= total;
    }

    /** 已经花的节点数。 */
    int used() {
        return spent;
    }

    /** 目标换了:这一趟重新开始,总预算重新按新目标的估价定(调用方指定的仍是它)。 */
    void restart() {
        total = fixed;
        spent = 0;
    }
}
