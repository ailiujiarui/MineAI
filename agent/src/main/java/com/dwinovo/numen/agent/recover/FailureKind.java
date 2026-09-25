package com.dwinovo.numen.agent.recover;

/**
 * 失败的种类。分类的意义:同一种失败配同一种恢复法,别每次都重新规划。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public enum FailureKind {
    /** 没失败。 */
    NONE,
    /** 引用了不存在的物品/方块 id。 */
    UNKNOWN_ITEM,
    /** 手上没有能干活儿的工具。 */
    NO_TOOL,
    /** 目标够不着 / 路被堵死。 */
    UNREACHABLE,
    /** 目标没了(资源被采光、方块被换掉)。 */
    TARGET_GONE,
    /** 背包满了,收不进东西。 */
    INVENTORY_FULL,
    /** 受威胁(挨打、身边有敌对生物)。 */
    THREATENED,
    /** 连着几轮原地不动。 */
    STALLED,
    /** 归不了类,但确实失败了。 */
    UNKNOWN
}
