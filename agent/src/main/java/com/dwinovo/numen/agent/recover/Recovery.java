package com.dwinovo.numen.agent.recover;

/**
 * 恢复动作。纯 JVM,不碰 Minecraft。
 */
public enum Recovery {
    /** 原样重试一次。 */
    RETRY,
    /** 换个同类的目标。 */
    RETARGET,
    /** 先弄到合适的工具。 */
    ACQUIRE_TOOL,
    /** 先靠近 / 重新寻路。 */
    APPROACH,
    /** 回到规划层重算。 */
    REPLAN,
    /** 问主人 / 消歧义。 */
    ASK,
    /** 交给本能(逃命)或直接收工。 */
    ABORT
}
