package com.dwinovo.numen.agent.bt;

/**
 * 行为树节点。一 tick 一次,回三态之一。
 *
 * <p>行为树是为"已知流程"准备的确定性执行层:顺序、回退、重试、超时都在这里,叶子是带
 * 世界后置校验的原子动作。它替代 ReAct 里"每一步都问模型"的内循环。
 *
 * <p>纯 JVM,不碰 Minecraft。叶子的具体动作由宿主(api/core)实现。
 */
public interface Node {

    enum Status {
        RUNNING,
        SUCCESS,
        FAILURE
    }

    /** 推进一 tick。 */
    Status tick(BtContext ctx);

    /** 回到初始态,供重新选中/重试时用。 */
    default void reset() {
    }

    default String describe() {
        return getClass().getSimpleName();
    }
}
