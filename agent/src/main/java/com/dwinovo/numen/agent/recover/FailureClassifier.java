package com.dwinovo.numen.agent.recover;

import java.util.List;

/**
 * 失败分类:多个标记同时出现时,按"哪个更该先处理"归并成一个种类。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class FailureClassifier {

    /**
     * 谁优先,谁先被处理:安全第一,其次是干不下去的资源问题,再是位置问题,最后才是打转/不明。
     */
    private static final List<FailureKind> PRIORITY = List.of(
            FailureKind.THREATENED,
            FailureKind.INVENTORY_FULL,
            FailureKind.NO_TOOL,
            FailureKind.UNREACHABLE,
            FailureKind.TARGET_GONE,
            FailureKind.STALLED,
            FailureKind.UNKNOWN_ITEM);

    private FailureClassifier() {}

    public static FailureKind classify(FailureSignal signal) {
        if (signal == null) {
            return FailureKind.NONE;
        }
        for (FailureKind kind : PRIORITY) {
            if (signal.has(kind)) {
                return kind;
            }
        }
        if (signal.has(FailureKind.UNKNOWN)) {
            return FailureKind.UNKNOWN;
        }
        return signal.reason().isBlank() ? FailureKind.NONE : FailureKind.UNKNOWN;
    }
}
