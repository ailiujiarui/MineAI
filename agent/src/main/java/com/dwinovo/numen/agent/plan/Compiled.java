package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.Node;

/**
 * 编译结果。
 *
 * <ul>
 *   <li>{@code ok}:编译出了一棵能跑的树(可能因为库存已经够而是空树);</li>
 *   <li>不 ok:{@link #unmet()} 说清卡在哪(成环/歧义),{@link #tree()} 是编译到一半的部分树。</li>
 * </ul>
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record Compiled(boolean ok, BehaviorTree tree, Missing.Unresolved unmet) {

    /** 库存已经满足:一棵立刻成功的空树。 */
    public static Compiled satisfied() {
        return new Compiled(true,
                new BehaviorTree("satisfied", new ActionNode("noop", ctx -> Node.Status.SUCCESS)), null);
    }

    public static Compiled ok(BehaviorTree tree) {
        return new Compiled(true, tree, null);
    }

    public static Compiled unmet(Node partial, Missing.Unresolved unmet) {
        return new Compiled(false, new BehaviorTree("unmet", partial), unmet);
    }
}
