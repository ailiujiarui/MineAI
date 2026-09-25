package com.dwinovo.numen.agent.recover;

/**
 * 恢复策略:失败种类 → 恢复动作。
 *
 * <p>"换目标/补工具/靠近/换法/询问"这一条链子,就是 MineAI 的 {@code RecoveryPolicy}。分类
 * 和策略都是纯映射,所以能脱离世界单测。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class RecoveryPolicy {

    private RecoveryPolicy() {}

    public static Recovery choose(FailureKind kind) {
        return switch (kind) {
            case THREATENED -> Recovery.ABORT;          // 安全交给本能链,这里不抢身体
            case INVENTORY_FULL -> Recovery.REPLAN;      // 先卸货/入箱,重算
            case NO_TOOL -> Recovery.ACQUIRE_TOOL;
            case UNREACHABLE -> Recovery.APPROACH;
            case TARGET_GONE -> Recovery.RETARGET;
            case STALLED -> Recovery.REPLAN;
            case UNKNOWN_ITEM -> Recovery.ASK;
            case UNKNOWN -> Recovery.REPLAN;
            case NONE -> Recovery.RETRY;
        };
    }
}
