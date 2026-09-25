package com.dwinovo.numen.agent.recover;

import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 恢复装饰:子节点失败时先分类、按策略造一棵恢复子树修一修,修好再让孩子重试。
 *
 * <p>这就是"失败是局部的、有界的"落地:一次 {@code mine} 卡住不再拖垮整条长链——先补工具/
 * 靠近/换目标,再接着干;修不好、或修的次数到顶,才把失败往上抛。
 *
 * <p>纯 JVM,不碰 Minecraft。恢复子树由宿主用世界操作造。
 */
public final class Recover implements Node {

    public static final int DEFAULT_MAX_RECOVERIES = 3;

    private final Node child;
    private final Supplier<FailureSignal> signal;
    private final Function<FailureKind, Node> recoveryFactory;
    private final int maxRecoveries;

    private Node active;
    private int recoveries;
    private FailureKind lastKind;
    private Recovery lastRecovery;

    /**
     * @param signal          失败时问现场要一个 {@link FailureSignal}
     * @param recoveryFactory 按种类造恢复子树;回 {@code null} = 修不了,直接失败
     */
    public Recover(Node child, Supplier<FailureSignal> signal, Function<FailureKind, Node> recoveryFactory) {
        this(child, signal, recoveryFactory, DEFAULT_MAX_RECOVERIES);
    }

    public Recover(Node child, Supplier<FailureSignal> signal, Function<FailureKind, Node> recoveryFactory,
                   int maxRecoveries) {
        this.child = child;
        this.signal = signal;
        this.recoveryFactory = recoveryFactory;
        this.maxRecoveries = Math.max(1, maxRecoveries);
    }

    /** 最近一次归出来的失败种类;没失败过是 {@code null}。 */
    public FailureKind lastKind() {
        return lastKind;
    }

    /** 最近一次选了哪种恢复。 */
    public Recovery lastRecovery() {
        return lastRecovery;
    }

    @Override
    public Status tick(BtContext ctx) {
        if (active != null) {
            Status repaired = active.tick(ctx);
            if (repaired == Status.RUNNING) {
                return Status.RUNNING;
            }
            active = null;
            if (repaired == Status.SUCCESS) {
                child.reset();
                return Status.RUNNING;   // 修好了,下 tick 让孩子重试
            }
            return Status.FAILURE;       // 修不动
        }

        Status status = child.tick(ctx);
        if (status != Status.FAILURE) {
            return status;
        }
        if (recoveries >= maxRecoveries) {
            return Status.FAILURE;
        }
        FailureKind kind = FailureClassifier.classify(signal.get());
        Recovery recovery = RecoveryPolicy.choose(kind);
        lastKind = kind;
        lastRecovery = recovery;
        Node remedy = recoveryFactory == null ? null : recoveryFactory.apply(kind);
        if (remedy == null) {
            return Status.FAILURE;
        }
        recoveries++;
        active = remedy;
        return Status.RUNNING;
    }

    @Override
    public void reset() {
        active = null;
        recoveries = 0;
        lastKind = null;
        lastRecovery = null;
        child.reset();
    }
}
