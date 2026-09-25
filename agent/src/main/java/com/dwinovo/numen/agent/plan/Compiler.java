package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.Node;
import com.dwinovo.numen.agent.bt.Sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 把高层目标编译成一棵行为树——"已知流程编译成程序,把 LLM 移出内循环"这一步。
 *
 * <p>先用 {@link GapCalculator} 算出依赖树,再把每个节点落成动作:采集叶子交
 * {@link Actions#gather};合成节点排成"备工作站 → 先满足各原料 → 做"的 {@link Sequence}。
 * 算不出来的地方落成一个永远失败的动作,并在 {@link Compiled#unmet()} 里带出来。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Compiler {

    private Compiler() {}

    public static Compiled compile(GoalTask goal, Counts have, RecipeBook book, Actions actions) {
        Missing missing = GapCalculator.missing(goal, have, book);
        if (missing == null) {
            return Compiled.satisfied();
        }
        List<Missing.Unresolved> unmet = new ArrayList<>();
        Node root = toNode(missing, actions, unmet);
        if (!unmet.isEmpty()) {
            return Compiled.unmet(root, unmet.get(0));
        }
        return Compiled.ok(new BehaviorTree("plan:" + goal.item(), root));
    }

    private static Node toNode(Missing missing, Actions actions, List<Missing.Unresolved> unmet) {
        return switch (missing) {
            case Missing.Gather gather -> actions.gather(gather.item(), gather.count());
            case Missing.Craft craft -> {
                List<Node> steps = new ArrayList<>();
                if (craft.recipe().needsStation()) {
                    steps.add(actions.ensureStation(craft.recipe().station()));
                }
                for (Missing input : craft.inputs()) {
                    steps.add(toNode(input, actions, unmet));
                }
                steps.add(actions.craft(craft.recipe(), craft.times()));
                yield new Sequence(steps);
            }
            case Missing.Unresolved unresolved -> {
                unmet.add(unresolved);
                yield new ActionNode("unresolved:" + unresolved.item(), ctx -> Node.Status.FAILURE);
            }
        };
    }
}
