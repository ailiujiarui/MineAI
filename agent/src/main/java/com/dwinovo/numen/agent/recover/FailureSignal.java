package com.dwinovo.numen.agent.recover;

import java.util.Set;

/**
 * 一次失败的现场:带哪些 {@link FailureKind} 标记,外加一句理由。
 *
 * <p>用<b>类型化标记</b>而不是拿关键词猜理由——"失败的原因"由发的一方说清,分类器只做优先级
 * 归并。理由文本只兜底。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record FailureSignal(Set<FailureKind> flags, String reason) {

    public FailureSignal {
        flags = Set.copyOf(flags);
        reason = reason == null ? "" : reason;
    }

    public static final FailureSignal EMPTY = new FailureSignal(Set.of(), "");

    public static FailureSignal of(FailureKind... flags) {
        return new FailureSignal(Set.of(flags), "");
    }

    public static FailureSignal of(String reason, FailureKind... flags) {
        return new FailureSignal(Set.of(flags), reason);
    }

    public boolean has(FailureKind kind) {
        return flags.contains(kind);
    }
}
