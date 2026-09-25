package com.dwinovo.numen.agent.plan;

import java.util.List;

/**
 * 一条配方:产出什么、要什么、在不在工作台上。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param output      产出物品 id
 * @param outputCount 一次产出几个
 * @param ingredients 原料
 * @param station     需要的工作站物品 id(工作台/熔炉/…);{@code null} = 背包里就能做
 */
public record Recipe(String output, int outputCount, List<ItemStack> ingredients, String station) {

    public Recipe {
        if (outputCount < 1) {
            outputCount = 1;
        }
        ingredients = List.copyOf(ingredients);
    }

    public boolean needsStation() {
        return station != null && !station.isBlank();
    }

    /** 用来判断"两条配方是不是同一种做法"。 */
    public String signature() {
        StringBuilder sb = new StringBuilder(output).append('@').append(station == null ? "-" : station);
        for (ItemStack i : ingredients) {
            sb.append(' ').append(i.item()).append('x').append(i.count());
        }
        return sb.toString();
    }
}
