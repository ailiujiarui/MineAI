package com.dwinovo.numen.agent.plan;

import java.util.List;

/**
 * 配方表:某个物品有哪些做法。空列表 = 没有配方,只能靠采集。
 *
 * <p>宿主给(服务端配方/知识包),编译器只查不问。纯 JVM,不碰 Minecraft。
 */
@FunctionalInterface
public interface RecipeBook {

    List<Recipe> recipesFor(String item);

    static RecipeBook empty() {
        return item -> List.of();
    }
}
