package com.dwinovo.numen.agent.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 依赖计算:递归展开配方,扣掉真实库存,算出"要拿到这个,还差哪些原料"。
 *
 * <p>这就是 MineAI {@code GapCalculator} 的纯 JVM 版。三件事:没有配方的物品落成
 * {@link Missing.Gather};有配方的先展开原料;多条做法分不清落成 {@link Missing.Unresolved}。
 * 展开时撞回依赖链上已经有的物品(压缩方块互指、可逆配方等)则落成 {@link Missing.Cycle} 叶子,
 * 不再往下钻,也不让整棵树失败——上层拿到的是"其余部分照计划"的部分计划。
 *
 * <p>库存不是每次全量重算:展开时用一份"已计划"账本记下每个物品已经排进去多少,兄弟分支
 * 之间不再重复吃同一份库存——否则"两个成品都要铁"会各按全量库存算一遍,少采。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class GapCalculator {

    private GapCalculator() {}

    /** 目标已经满足回 {@code null}(什么都不用做)。 */
    public static Missing missing(GoalTask goal, Counts have, RecipeBook book) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(have, "have");
        Objects.requireNonNull(book, "book");
        return expand(goal.item(), goal.count(), goal.prefer(), have, book,
                new ArrayDeque<>(), new HashMap<>());
    }

    private static Missing expand(String item, int count, List<String> prefer, Counts have, RecipeBook book,
                                  Deque<String> stack, Map<String, Integer> reserved) {
        int available = Math.max(0, have.count(item) - reserved.getOrDefault(item, 0));
        int need = count - available;
        // 这一分支要占住 count 个:库存里能拿的也先算在它头上,兄弟分支再要同一种物品时看到的是
        // 扣完的库存。要是只在 need>0 时才记账,一份库存会被每个"刚好够"的兄弟分支反复复用——
        // 目标要 5 块木板、手里 1 根原木,却算成不用再采原木。
        reserved.merge(item, count, Integer::sum);
        if (need <= 0) {
            return null;   // 库存就够
        }
        if (stack.contains(item)) {
            // 这条链又绕回自己:当成叶子,别再无谓地往下展开(压缩方块一类的可逆配方会无限互指)。
            return new Missing.Cycle(item, need);
        }
        List<Recipe> recipes = book.recipesFor(item);
        if (recipes.isEmpty()) {
            return new Missing.Gather(item, need);
        }
        Recipe recipe = choose(recipes, prefer);
        if (recipe == null) {
            return new Missing.Unresolved(item, need, "ambiguous recipe",
                    recipes.stream().map(Recipe::signature).toList());
        }
        int times = (need + recipe.outputCount() - 1) / recipe.outputCount();

        stack.push(item);
        List<Missing> inputs = new ArrayList<>();
        for (ItemStack ingredient : recipe.ingredients()) {
            Missing child = expand(ingredient.item(), ingredient.count() * times, prefer, have, book, stack, reserved);
            if (child != null) {
                inputs.add(child);
            }
        }
        stack.pop();
        return new Missing.Craft(item, need, recipe, times, inputs);
    }

    /**
     * 多条配方怎么选:偏好点名优先;否则唯一一条不用工作站的(背包能做的)优先;再否则看是不是
     * 其实同一种做法;都分不清就回 {@code null},交给上层消歧义。
     */
    static Recipe choose(List<Recipe> recipes, List<String> prefer) {
        if (recipes.size() == 1) {
            return recipes.get(0);
        }
        for (String pick : prefer) {
            for (Recipe recipe : recipes) {
                if (recipe.ingredients().stream().anyMatch(i -> i.item().equals(pick))) {
                    return recipe;
                }
            }
        }
        List<Recipe> noStation = recipes.stream().filter(r -> !r.needsStation()).toList();
        if (noStation.size() == 1) {
            return noStation.get(0);
        }
        long distinct = recipes.stream().map(Recipe::signature).distinct().count();
        if (distinct == 1) {
            return recipes.get(0);
        }
        return null;
    }
}
