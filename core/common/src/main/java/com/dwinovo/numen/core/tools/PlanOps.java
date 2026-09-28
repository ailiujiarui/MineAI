package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.Node;
import com.dwinovo.numen.agent.plan.Actions;
import com.dwinovo.numen.agent.plan.Compiled;
import com.dwinovo.numen.agent.plan.Compiler;
import com.dwinovo.numen.agent.plan.Counts;
import com.dwinovo.numen.agent.plan.GoalTask;
import com.dwinovo.numen.agent.plan.Missing;
import com.dwinovo.numen.agent.plan.Recipe;
import com.dwinovo.numen.agent.plan.RecipeBook;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.plan.CompanionCounts;
import com.dwinovo.numen.core.plan.ServerRecipeBook;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code plan_make} 的业务半边:把纯 JVM 的规划层接到活服务端——同伴背包当 {@link Counts},
 * 活配方表当 {@link RecipeBook},问"要拿到这个还差什么、按什么顺序做"。
 *
 * <p>编译器 {@link Compiler#compile} 一边落树一边调 {@link Actions}。这里交一个只记账的
 * {@link Recorder}:采集叶子按物品归并成"还差的原料",craft / 备工作站按调用顺序记成产线步骤,
 * 不真动世界——这个工具只出计划,执行仍走 gather/mine、craft 与各工作站的工具。
 *
 * <p>库存扣减、成环、多条做法分不清都交规划层判。成环不再硬失败:规划层把成环处当采集叶子,
 * 这里就把已经算通的部分当部分计划交出去,并在 {@code unresolved} 里点名没算通的节点;真的
 * 一步都算不出才如实回执。绝不拿"做完了"糊弄。
 */
public final class PlanOps {

    public String plan(String item_id, Integer count, NumenPlayer self) {
        Item target = ToolArgs.parseItem(item_id);
        int want = count == null ? 1 : Math.clamp(count, 1, 256);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("planning needs a server level.").toJson();
        }
        String id = BuiltInRegistries.ITEM.getKey(target).toString();

        Counts have = new CompanionCounts(self.getInventory());
        RecipeBook book = ServerRecipeBook.forLevel(level, have);
        Recorder recorder = new Recorder();
        Compiled compiled = Compiler.compile(GoalTask.obtain(id, want), have, book, recorder);
        List<String> unresolved = compiled.unresolved();

        // 什么都没记下、又没编译成功,才是真的算不出来(比如目标本身就有多条做法分不清)。
        // 只要记下了哪怕一步,就把它当部分计划交出去,别拿 bare "cannot plan" 埋掉已经算出的部分。
        if (!compiled.ok() && recorder.steps.isEmpty()) {
            Missing.Unresolved unmet = compiled.unmet();
            String options = unmet.options().isEmpty() ? ""
                    : " Options: " + String.join(" | ", unmet.options()) + ".";
            return TaskResult.fail("cannot plan " + want + "x " + id + ": " + unmet.reason()
                    + " at " + unmet.item() + "." + options).toJson();
        }
        if (recorder.steps.isEmpty()) {
            return TaskResult.ok("already satisfied: you carry " + want + "x " + id
                    + " — nothing to make.").toJson();
        }

        List<Map<String, Object>> gather = new ArrayList<>();
        for (Map.Entry<String, Integer> e : recorder.gather.entrySet()) {
            gather.add(Map.of("item", e.getKey(), "count", e.getValue()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", id);
        data.put("count", want);
        data.put("satisfied", false);
        data.put("gather", gather);
        data.put("steps", recorder.steps);
        if (!unresolved.isEmpty()) {
            data.put("unresolved", unresolved);
        }

        StringBuilder msg = new StringBuilder(unresolved.isEmpty() ? "plan for " : "partial plan for ")
                .append(want).append("x ").append(id)
                .append(" — not all in your inventory yet.");
        if (gather.isEmpty()) {
            msg.append("\nGather: nothing missing; the materials you carry cover the steps below.");
        } else {
            msg.append("\nGather:");
            for (Map<String, Object> g : gather) {
                msg.append("\n  - ").append(g.get("count")).append("x ").append(g.get("item"));
            }
        }
        msg.append("\nSteps (in order):");
        int n = 1;
        for (String step : recorder.steps) {
            msg.append("\n  ").append(n++).append(". ").append(step);
        }
        if (!unresolved.isEmpty()) {
            msg.append("\nUnresolved (cyclic or ambiguous; cannot be planned through):");
            for (String item : unresolved) {
                msg.append("\n  - ").append(item);
            }
        }
        msg.append("\nThis only plans; nothing has been gathered or made yet.");
        return TaskResult.ok(msg.toString(), data).toJson();
    }

    /** 只记账的 {@link Actions}:编译时按顺序收下每一步,返回的空动作不会被真正 tick。 */
    private static final class Recorder implements Actions {

        private final List<String> steps = new ArrayList<>();
        private final Map<String, Integer> gather = new LinkedHashMap<>();

        @Override
        public Node gather(String item, int count) {
            gather.merge(item, count, Integer::sum);
            steps.add("gather " + count + "x " + item);
            return noop("gather:" + item);
        }

        @Override
        public Node craft(Recipe recipe, int times) {
            steps.add("craft " + recipe.output() + " x" + times
                    + (recipe.outputCount() > 1 ? " (" + recipe.outputCount() + " per run)" : ""));
            return noop("craft:" + recipe.output());
        }

        @Override
        public Node ensureStation(String station) {
            steps.add("have " + station + " at hand");
            return noop("station:" + station);
        }

        private static Node noop(String name) {
            return new ActionNode(name, ctx -> Node.Status.SUCCESS);
        }
    }
}
