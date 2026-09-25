package com.dwinovo.numen.agent.utility;

import java.util.List;

/**
 * 仲裁器:每 tick 在候选里选分最高的,并带迟滞(hysteresis)防止来回抖。
 *
 * <p>迟滞的意义:当前行为只要没被挑战者<b>明显</b>超过就继续做。没有它,"逃命 vs 挖矿"两边
 * 分数在阈值附近来回穿,身体就会原地抽。真安全行为可以给高底分,直接压过。
 *
 * <p>它是唯一一个"谁拿身体"的决定点——BT 和 LLM/JEV 都只是给它提供分数或选项,不各自
 * 调度。这正是 Numen 原来 {@code CompanionBrain} 竞价的位置。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class UtilityArbiter {

    /** 挑战者要超过当前行为这么多分才顶掉它。 */
    public static final double DEFAULT_HYSTERESIS = 0.1D;

    private final double hysteresis;
    private String currentId;

    public UtilityArbiter() {
        this(DEFAULT_HYSTERESIS);
    }

    public UtilityArbiter(double hysteresis) {
        this.hysteresis = Math.max(0.0D, hysteresis);
    }

    public String currentId() {
        return currentId;
    }

    public void clear() {
        currentId = null;
    }

    /** 选出这一刻该跑的行为;没有候选回 {@code null}。 */
    public BehaviorOption select(ControlState state, List<BehaviorOption> options) {
        BehaviorOption best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BehaviorOption option : options) {
            double score = option.score(state);
            if (score > bestScore) {
                bestScore = score;
                best = option;
            }
        }
        if (best == null) {
            currentId = null;
            return null;
        }
        BehaviorOption current = current(state, options);
        if (current != null && current != best && bestScore <= current.score(state) + hysteresis) {
            return current;   // 挑战者没明显更优,继续当前
        }
        currentId = best.id();
        return best;
    }

    private BehaviorOption current(ControlState state, List<BehaviorOption> options) {
        if (currentId == null) {
            return null;
        }
        for (BehaviorOption option : options) {
            if (option.id().equals(currentId)) {
                return option;
            }
        }
        return null;
    }
}
