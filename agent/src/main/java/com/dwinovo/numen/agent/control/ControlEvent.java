package com.dwinovo.numen.agent.control;

/**
 * 控制层对外说的事:选了哪个行为、成功、失败、被抢占、外部事件。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record ControlEvent(String kind, String text, long ts) {

    public static final String SELECTED = "behavior.selected";
    public static final String SUCCESS = "behavior.success";
    public static final String FAILURE = "behavior.failure";
    public static final String PREEMPTED = "behavior.preempted";

    public static ControlEvent of(String kind, String text) {
        return new ControlEvent(kind, text, System.currentTimeMillis());
    }
}
