package com.dwinovo.numen.agent.plan;

import java.util.List;

/**
 * 目标的依赖树——"还差什么、怎么一步步凑出来"。
 *
 * <p>四种节点:
 * <ul>
 *   <li>{@link Gather}:没有配方,只能去世界里弄到(挖/采/捡);</li>
 *   <li>{@link Craft}:有配方,先备齐 {@link Craft#inputs()},再做 {@link Craft#times()} 次;</li>
 *   <li>{@link Cycle}:展开时撞上自己所在的依赖链,不能再往下算,当成采集叶子,由上层把这一环
 *       告诉模型;整棵树照旧编译,只是成了部分计划;</li>
 *   <li>{@link Unresolved}:多条做法分不清。由模型/偏好消歧义后再来。</li>
 * </ul>
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public sealed interface Missing permits Missing.Gather, Missing.Craft, Missing.Cycle, Missing.Unresolved {

    String item();

    int count();

    /** 没配方,得去世界里弄。 */
    record Gather(String item, int count) implements Missing {}

    /** 有配方:先满足 inputs,再做 times 次。 */
    record Craft(String item, int count, Recipe recipe, int times, List<Missing> inputs) implements Missing {
        public Craft {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * 依赖成环:沿着这条链再展开又会回到 {@code item}({@code a -> b -> a} 或更长的环)。
     * 当成叶子,想拿它只能去世界里弄,不能靠这条配方往下算。
     */
    record Cycle(String item, int count) implements Missing {}

    /** 算不出来。{@code options} 是可选的做法,交给上层消歧义。 */
    record Unresolved(String item, int count, String reason, List<String> options) implements Missing {
        public Unresolved {
            options = List.copyOf(options);
        }
    }
}
