package com.dwinovo.numen.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GapCalculatorTest {

    private static final Recipe PLANKS =
            new Recipe("minecraft:oak_planks", 4, List.of(ItemStack.of("minecraft:oak_log", 1)), null);
    private static final Recipe STICK =
            new Recipe("minecraft:stick", 4, List.of(ItemStack.of("minecraft:oak_planks", 2)), null);

    private static RecipeBook book(Recipe... recipes) {
        Map<String, List<Recipe>> map = new HashMap<>();
        for (Recipe recipe : recipes) {
            map.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
        }
        return item -> map.getOrDefault(item, List.of());
    }

    @Test
    void noRecipeMeansGather() {
        Missing missing = GapCalculator.missing(
                GoalTask.obtain("minecraft:iron_ore", 3), Counts.empty(), RecipeBook.empty());
        assertTrue(missing instanceof Missing.Gather gather
                && gather.item().equals("minecraft:iron_ore") && gather.count() == 3);
    }

    @Test
    void alreadySatisfiedIsNull() {
        assertNull(GapCalculator.missing(GoalTask.obtain("minecraft:iron_ore", 3),
                Counts.of(Map.of("minecraft:iron_ore", 5)), RecipeBook.empty()));
    }

    @Test
    void recipesExpandRecursively() {
        Missing missing = GapCalculator.missing(
                GoalTask.obtain("minecraft:stick", 4), Counts.empty(), book(PLANKS, STICK));

        assertTrue(missing instanceof Missing.Craft, "stick 有配方");
        Missing.Craft stick = (Missing.Craft) missing;
        Missing planks = stick.inputs().get(0);
        assertTrue(planks instanceof Missing.Craft, "planks 也有配方");
        Missing log = ((Missing.Craft) planks).inputs().get(0);
        assertTrue(log instanceof Missing.Gather gather && gather.item().equals("minecraft:oak_log"),
                "一路展到没有配方的原木");
    }

    @Test
    void existingStockShrinksTheGap() {
        // 要 8 个木板 = 2 个原木,手里已有 1 个 → 还差 1 个原木。
        Missing missing = GapCalculator.missing(GoalTask.obtain("minecraft:oak_planks", 8),
                Counts.of(Map.of("minecraft:oak_log", 1)), book(PLANKS));
        assertTrue(missing instanceof Missing.Craft);
        Missing only = ((Missing.Craft) missing).inputs().get(0);
        assertTrue(only instanceof Missing.Gather gather && gather.count() == 1);
    }

    @Test
    void stockThatFullyCoversALeafLeavesNoInput() {
        Missing missing = GapCalculator.missing(GoalTask.obtain("minecraft:oak_planks", 4),
                Counts.of(Map.of("minecraft:oak_log", 1)), book(PLANKS));
        assertTrue(missing instanceof Missing.Craft craft && craft.inputs().isEmpty(),
                "原木已经有了,不用再采");
    }

    @Test
    void aRecipeThatDependsOnItselfIsUnresolved() {
        RecipeBook book = book(new Recipe("a", 1, List.of(ItemStack.of("a", 1)), null));
        Missing missing = GapCalculator.missing(GoalTask.obtain("a", 1), Counts.empty(), book);
        assertTrue(missing instanceof Missing.Craft);
        assertTrue(((Missing.Craft) missing).inputs().get(0) instanceof Missing.Unresolved cycle
                && cycle.reason().contains("cycle"));
    }

    @Test
    void twoDistinctRecipesAreAmbiguous() {
        RecipeBook book = book(
                new Recipe("x", 1, List.of(ItemStack.of("p", 1)), "minecraft:crafting_table"),
                new Recipe("x", 1, List.of(ItemStack.of("q", 1)), "minecraft:furnace"));
        Missing missing = GapCalculator.missing(GoalTask.obtain("x", 1), Counts.empty(), book);
        assertTrue(missing instanceof Missing.Unresolved unresolved
                && unresolved.reason().contains("ambiguous") && unresolved.options().size() == 2);
    }

    @Test
    void aPreferencePicksTheNamedIngredient() {
        RecipeBook book = book(
                new Recipe("x", 1, List.of(ItemStack.of("p", 1)), "minecraft:crafting_table"),
                new Recipe("x", 1, List.of(ItemStack.of("q", 1)), "minecraft:crafting_table"));
        Missing missing = GapCalculator.missing(
                GoalTask.obtain("x", 1).preferring(List.of("q")), Counts.empty(), book);
        assertTrue(missing instanceof Missing.Craft craft
                && craft.recipe().ingredients().get(0).item().equals("q"));
    }
}
