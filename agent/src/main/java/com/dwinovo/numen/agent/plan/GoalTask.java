package com.dwinovo.numen.agent.plan;

import java.util.List;

/**
 * 要做的一件事:拿到某物品多少,以及用哪种做法(消歧义用的偏好)。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record GoalTask(String item, int count, List<String> prefer) {

    public GoalTask {
        if (count < 1) {
            count = 1;
        }
        prefer = List.copyOf(prefer);
    }

    public static GoalTask obtain(String item, int count) {
        return new GoalTask(item, count, List.of());
    }

    /** 偏好:多条配方时,优先选原料里点名了这些物品的那条。 */
    public GoalTask preferring(List<String> options) {
        return new GoalTask(item, count, options);
    }
}
