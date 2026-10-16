package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.MoveKind;

/**
 * 这次导航的行程:段状态机一路上搜了几次、几次没搜到这一段的终点(抬高了估价)、几次扔掉路重搜、几步走不下去、几步卡住、复核了几次前提。
 * 每种走法走完的步数、实际共用的刻数与最短耗时共多少刻,看得出哪种走法实际比几何最短慢。
 * 计数记在事件发生的那一处——与写日志的是同一处——不另行推算;随实际账({@link EditLedger},含实际走完的各种步)一起交出。
 */
public final class Journal {

    private int searches;
    private final List<Integer> expansions = new ArrayList<>();
    private final List<Long> searchNanos = new ArrayList<>();
    private final List<Double> gaps = new ArrayList<>();
    private int learned;
    private double routeCost;
    private int replans;
    private int blockages;
    private int stuck;
    private int rechecks;
    private final Map<MoveKind, Timing> timings = new EnumMap<>(MoveKind.class);

    /**
     * 一种走法走完的步的用时。
     *
     * @param steps     走完几步(冲劲把身体带过几步、落在后面某一步上时,带过的几步也算走完)
     * @param ticks     这些步实际共用的刻数(一次走完几步的,按各步最短耗时的比例分)
     * @param estimated 这些步的几何最短耗时(运动学加挖掘的刻数)共多少刻
     */
    public record Timing(int steps, double ticks, double estimated) {}

    /**
     * 一次搜索有了结论;{@code expanded} 是它展开了几个节点,{@code nanos} 是它在工作线程上跑了多久,{@code learned} 是它没搜到这一段的终点、
     * 展开过的格子的估价抬高了,{@code gap} 是交出的路线证到的界({@link com.dwinovo.numen.pathing.search.SearchResult#gap})。
     */
    void searched(int expanded, long nanos, boolean learned, double gap) {
        searches++;
        expansions.add(expanded);
        searchNanos.add(nanos);
        gaps.add(gap);
        if (learned) {
            this.learned++;
        }
    }

    /** 扔掉在走的路,从身体脚下重搜。 */
    void replanned() {
        replans++;
    }

    /** 一步走不下去。 */
    void blocked() {
        blockages++;
    }

    /** 一步卡住(做了一阵超过期限)。 */
    void gotStuck() {
        stuck++;
    }

    /** 复核了一步的前提。 */
    void rechecked() {
        rechecks++;
    }

    /** 走完了一步,它在规划里的代价是 {@code cost}。 */
    void walked(double cost) {
        routeCost += cost;
    }

    /** 一步 {@code kind} 走完:实际用了 {@code ticks} 刻,几何最短耗时是 {@code estimated} 刻。 */
    void timed(MoveKind kind, double ticks, double estimated) {
        timings.merge(kind, new Timing(1, ticks, estimated),
                (a, b) -> new Timing(a.steps + b.steps, a.ticks + b.ticks, a.estimated + b.estimated));
    }

    /** 搜索有了结论的次数(不含为"没有路"而做的诊断)。 */
    public int searches() {
        return searches;
    }

    /** 每次搜索展开的节点数,按先后。 */
    public List<Integer> expansions() {
        return Collections.unmodifiableList(expansions);
    }

    /** 每次搜索在工作线程上跑了多少纳秒,按先后。 */
    public List<Long> searchNanos() {
        return Collections.unmodifiableList(searchNanos);
    }

    /** 每次搜索证到的界(路线的价钱 ÷ 这一段最优价的下界;没交出整段为 NaN),按先后。 */
    public List<Double> gaps() {
        return Collections.unmodifiableList(gaps);
    }

    /** 实际走完的各步按规划算的代价之和:这条路线值多少钱。 */
    public double routeCost() {
        return routeCost;
    }

    /** 其中没搜到这一段的终点、抬高了估价往边界推进的次数。 */
    public int learned() {
        return learned;
    }

    /** 扔掉路重搜的次数。 */
    public int replans() {
        return replans;
    }

    /** 走不下去的次数(每一步在这一趟里记为不通,以后的搜索不再走它)。 */
    public int blockages() {
        return blockages;
    }

    /** 卡住的次数。 */
    public int stuck() {
        return stuck;
    }

    /** 复核前提的次数。 */
    public int rechecks() {
        return rechecks;
    }

    /** 每种走法走完的步的用时;没走过的走法不在里面。 */
    public Map<MoveKind, Timing> timings() {
        return Collections.unmodifiableMap(timings);
    }
}
