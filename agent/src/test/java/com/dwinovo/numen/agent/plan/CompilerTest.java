package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {

    private static final Recipe PLANKS =
            new Recipe("minecraft:oak_planks", 4, List.of(ItemStack.of("minecraft:oak_log", 1)), null);
    private static final Recipe STICK =
            new Recipe("minecraft:stick", 4, List.of(ItemStack.of("minecraft:oak_planks", 2)), null);

    private static final Actions ACTIONS = new Actions() {
        @Override
        public Node gather(String item, int count) {
            return new ActionNode("gather:" + item, ctx -> Node.Status.SUCCESS);
        }

        @Override
        public Node craft(Recipe recipe, int times) {
            return new ActionNode("craft:" + recipe.output(), ctx -> Node.Status.SUCCESS);
        }

        @Override
        public Node ensureStation(String station) {
            return new ActionNode("station:" + station, ctx -> Node.Status.SUCCESS);
        }
    };

    private static RecipeBook book(Recipe... recipes) {
        Map<String, List<Recipe>> map = new HashMap<>();
        for (Recipe recipe : recipes) {
            map.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
        }
        return item -> map.getOrDefault(item, List.of());
    }

    private static Node.Status tickOne(Compiled compiled) {
        BtContext ctx = new BtContext();
        ctx.beginTick(1, () -> "");
        // Sequence 里全是同步成功的动作,一 tick 走完。
        Node.Status status = compiled.tree().tick(ctx);
        if (status == Node.Status.SUCCESS) {
            return status;
        }
        ctx.beginTick(2, () -> "");
        return compiled.tree().tick(ctx);
    }

    @Test
    void compilesARecipeChainIntoATreeThatSucceeds() {
        Compiled compiled = Compiler.compile(
                GoalTask.obtain("minecraft:stick", 4), Counts.empty(), book(PLANKS, STICK), ACTIONS);
        assertTrue(compiled.ok());
        assertEquals(Node.Status.SUCCESS, tickOne(compiled));
    }

    @Test
    void anAmbiguousGoalCompilesToAnUnmetResult() {
        RecipeBook book = book(
                new Recipe("x", 1, List.of(ItemStack.of("p", 1)), "minecraft:crafting_table"),
                new Recipe("x", 1, List.of(ItemStack.of("q", 1)), "minecraft:furnace"));
        Compiled compiled = Compiler.compile(GoalTask.obtain("x", 1), Counts.empty(), book, ACTIONS);
        assertFalse(compiled.ok());
        assertNotNull(compiled.unmet());
        assertTrue(compiled.unmet().reason().contains("ambiguous"));
    }

    @Test
    void anAlreadySatisfiedGoalIsAnOkNoop() {
        Compiled compiled = Compiler.compile(GoalTask.obtain("minecraft:dirt", 1),
                Counts.of(Map.of("minecraft:dirt", 1)), RecipeBook.empty(), ACTIONS);
        assertTrue(compiled.ok());
        assertEquals(Node.Status.SUCCESS, tickOne(compiled));
    }

    @Test
    void aCyclicGoalCompilesToAPartialPlan() {
        // a -> a 成环:不该硬失败,把成环处当采集叶子,整份计划标出 unresolved。
        RecipeBook book = book(new Recipe("a", 1, List.of(ItemStack.of("a", 1)), null));
        Compiled compiled = Compiler.compile(GoalTask.obtain("a", 1), Counts.empty(), book, ACTIONS);
        assertTrue(compiled.ok(), "成环只让计划变成部分计划");
        assertTrue(compiled.unresolved().contains("a"), "成环节点要能报出名字");
        assertEquals(Node.Status.SUCCESS, tickOne(compiled));
    }
}
