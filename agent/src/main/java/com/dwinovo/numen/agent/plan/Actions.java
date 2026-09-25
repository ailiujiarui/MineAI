package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.Node;

/**
 * 把依赖树落成真正会动的节点。宿主用世界操作实现:采、做、备工作站。
 *
 * <p>编译器只负责"先做什么后做什么",不碰世界。纯 JVM,不碰 Minecraft。
 */
public interface Actions {

    /** 去世界里弄到某物品 count 个(挖/采/捡)。 */
    Node gather(String item, int count);

    /** 在 {@code times} 次里做出配方产出。 */
    Node craft(Recipe recipe, int times);

    /** 确保工作站存在且够得着(工作台/熔炉),做完才能 craft。 */
    Node ensureStation(String station);
}
