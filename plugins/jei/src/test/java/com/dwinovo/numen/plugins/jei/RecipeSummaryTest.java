package com.dwinovo.numen.plugins.jei;

import java.util.Arrays;
import java.util.List;

/**
 * {@link RecipeSummary} 的纯单元测试。
 *
 * <p><b>为什么自带 main、不用 JUnit</b>:联动模块的 build.gradle 没配 JUnit,而这次改动
 * 不许碰 build 文件,所以这里用 {@code main} + 极简断言跑,不留一个编译不过的测试文件。
 * 运行:{@code java -cp <main classes>;<test classes> com.dwinovo.numen.plugins.jei.RecipeSummaryTest}。
 *
 * <p><b>为什么不能进 GameTest</b>:本联动只跑在装了 JEI 的客户端上,JEI 的配方注册表是
 * 客户端运行期才有的;GameTest 起的是服务端,这里测的也只是不依赖 JEI/Minecraft 的汇总逻辑。
 */
public final class RecipeSummaryTest {

    public static void main(String[] args) {
        // 没有催化剂不占位
        assertEquals("", RecipeSummary.primary(List.of()));
        assertEquals("", RecipeSummary.primary(Arrays.asList(null, "", "   ")));

        // 只有一个:报它自己(通常就是公认的工作台)
        assertEquals("minecraft:crafting_table",
                RecipeSummary.primary(List.of("minecraft:crafting_table")));

        // 二到三个:照报
        assertEquals("a + b", RecipeSummary.primary(List.of("a", "b")));
        assertEquals("a + b + c", RecipeSummary.primary(List.of("a", "b", "c")));

        // 三个以上:只留前三个,并标注总共多少
        assertEquals("a + b + c (first 3 of 9)",
                RecipeSummary.primary(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));

        // 去重后只剩一个:一个工作台被多个模组重复注册,也只报一次
        assertEquals("minecraft:crafting_table", RecipeSummary.primary(
                List.of("minecraft:crafting_table", "minecraft:crafting_table")));

        // 去重后再截断:原版工作台在前,模组终端跟在后面
        assertEquals("minecraft:crafting_table + integratedterminals:part_terminal_storage"
                        + " + ae2:terminal (first 3 of 4)",
                RecipeSummary.primary(List.of(
                        "minecraft:crafting_table", "minecraft:crafting_table",
                        "integratedterminals:part_terminal_storage",
                        "ae2:terminal", "ae2:crafting_terminal")));

        System.out.println("RecipeSummaryTest passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
