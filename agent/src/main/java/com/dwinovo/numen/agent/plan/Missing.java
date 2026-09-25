package com.dwinovo.numen.agent.plan;

import java.util.List;

/**
 * 目标的依赖树——"还差什么、怎么一步步凑出来"。
 *
 * <p>三种节点:
 * <ul>
 *   <li>{@link Gather}:没有配方,只能去世界里弄到(挖/采/捡);</li>
 *   <li>{@link Craft}:有配方,先备齐 {@link Craft#inputs()},再做 {@link Craft#times()} 次;</li>
 *   <li>{@link Unresolved}:算不出来(配方成环,或有多条分不清)。由模型/偏好消歧义后再来。</li>
 * </ul>
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public sealed interface Missing permits Missing.Gather, Missing.Craft, Missing.Unresolved {

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

    /** 算不出来。{@code options} 是可选的做法,交给上层消歧义。 */
    record Unresolved(String item, int count, String reason, List<String> options) implements Missing {
        public Unresolved {
            options = List.copyOf(options);
        }
    }
}
