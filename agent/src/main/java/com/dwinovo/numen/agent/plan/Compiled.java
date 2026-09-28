package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.Node;

import java.util.List;

/**
 * 编译结果。
 *
 * <ul>
 *   <li>{@code ok}:编译出了一棵能跑的树(可能因为库存已经够而是空树);</li>
 *   <li>不 ok:{@link #unmet()} 说清卡在哪(多条做法分不清),{@link #tree()} 是编译到一半的部分树;</li>
 *   <li>{@link #unresolved()}:没能算通的节点(成环的、分不清的),按物品 id 记名。成环不再算失败,
 *       树照编译,只是这份计划不完整,交回上层如实说明。</li>
 * </ul>
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record Compiled(boolean ok, BehaviorTree tree, Missing.Unresolved unmet, List<String> unresolved) {

    public Compiled {
        unresolved = List.copyOf(unresolved);
    }

    /** 库存已经满足:一棵立刻成功的空树。 */
    public static Compiled satisfied() {
        return new Compiled(true,
                new BehaviorTree("satisfied", new ActionNode("noop", ctx -> Node.Status.SUCCESS)), null, List.of());
    }

    public static Compiled ok(BehaviorTree tree, List<String> unresolved) {
        return new Compiled(true, tree, null, unresolved);
    }

    public static Compiled unmet(Node partial, Missing.Unresolved unmet, List<String> unresolved) {
        return new Compiled(false, new BehaviorTree("unmet", partial), unmet, unresolved);
    }
}
