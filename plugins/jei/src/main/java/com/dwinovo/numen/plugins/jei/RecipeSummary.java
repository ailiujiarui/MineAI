package com.dwinovo.numen.plugins.jei;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把一组"机器/催化剂"收敛成给模型看的短句。
 *
 * <p>JEI 的一个类别可能挂着一长串催化剂(原版工作台 + 各模组的终端/合成器,见
 * {@code minecraft:crafting_table + integratedterminals:part_terminal_storage + ae2:...});
 * 整串报给模型又长又随装了什么模组而变,没法读也没法引用。这里只留主要的一到三个:
 * 只有一个就报那个(通常就是公认的工作台),否则取前 {@value #MAX_PRIMARY} 个并在截断时
 * 标注总共多少,保证同一配方每次输出一致。
 *
 * <p><b>为什么单独一个类</b>:它<b>不认识 JEI/Minecraft</b>,吃的是调用方渲染好的 id 字符串,
 * 所以能被纯单元测试覆盖(见 {@code RecipeSummaryTest});JEI 是客户端模组,GameTest 起不来。
 */
final class RecipeSummary {

    /** 一个类别最多报几个机器;超过的用 {@code (first N of M)} 标注。 */
    static final int MAX_PRIMARY = 3;

    private RecipeSummary() {}

    /**
     * 去掉空白与重复,保留输入顺序——JEI 的注册顺序通常是原版在前,首个即公认的工作台。
     */
    static List<String> distinct(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 主要机器的短句:没有给空串;一个就报它;多个只报前 {@value #MAX_PRIMARY} 个,
     * 更多时缀上 {@code (first N of M)}。
     */
    static String primary(List<String> values) {
        List<String> unique = distinct(values);
        if (unique.isEmpty()) {
            return "";
        }
        if (unique.size() <= MAX_PRIMARY) {
            return String.join(" + ", unique);
        }
        String head = String.join(" + ", unique.subList(0, MAX_PRIMARY));
        return head + " (first " + MAX_PRIMARY + " of " + unique.size() + ")";
    }
}
