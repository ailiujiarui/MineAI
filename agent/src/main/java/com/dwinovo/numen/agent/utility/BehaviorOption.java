package com.dwinovo.numen.agent.utility;

import com.dwinovo.numen.agent.bt.BehaviorTree;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一个候选行为:一棵行为树 + 它此刻值多少分。
 *
 * <p>仲裁器在候选之间选,选中的那棵树上身执行。{@link #treeFactory} 每次选都造一棵新树,
 * 免得上次跑了一半的节点状态串台。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param id             行为名(与 {@code activeId} 对齐)
 * @param description    人读用途
 * @param baseScore      底分(安全类行为给高底分)
 * @param considerations 考虑项
 * @param treeFactory    造一棵全新的行为树
 */
public record BehaviorOption(String id, String description, double baseScore,
                             List<Consideration> considerations, Supplier<BehaviorTree> treeFactory) {

    public BehaviorOption {
        considerations = List.copyOf(considerations);
    }

    /** 这个行为此刻的总分。 */
    public double score(ControlState state) {
        double total = baseScore;
        for (Consideration c : considerations) {
            total += c.score(state);
        }
        return total;
    }

    public BehaviorTree freshTree() {
        return treeFactory.get();
    }
}
