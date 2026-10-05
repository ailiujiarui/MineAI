package com.dwinovo.numen.plugins.jei;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RecipeSummary} 的纯单元测试:它不认识 JEI/Minecraft,只吃渲染好的 id 字符串,所以能在普通单测里跑
 * (JEI 的配方注册表只在客户端运行期才有,GameTest 起不来)。
 */
class RecipeSummaryTest {

    @Test
    void emptyOrBlankIsNothing() {
        assertEquals("", RecipeSummary.primary(List.of()));
        assertEquals("", RecipeSummary.primary(Arrays.asList(null, "", "   ")));
    }

    @Test
    void oneCatalystIsThatOne() {
        assertEquals("minecraft:crafting_table", RecipeSummary.primary(List.of("minecraft:crafting_table")));
    }

    @Test
    void upToThreeAreReportedAsIs() {
        assertEquals("a + b", RecipeSummary.primary(List.of("a", "b")));
        assertEquals("a + b + c", RecipeSummary.primary(List.of("a", "b", "c")));
    }

    @Test
    void moreThanThreeKeepTheFirstThreeAndSayHowMany() {
        assertEquals("a + b + c (first 3 of 9)",
                RecipeSummary.primary(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));
    }

    @Test
    void duplicatesCollapse() {
        assertEquals("minecraft:crafting_table",
                RecipeSummary.primary(List.of("minecraft:crafting_table", "minecraft:crafting_table")));
    }

    @Test
    void distinctThenTruncateKeepsVanillaFirst() {
        assertEquals("minecraft:crafting_table + integratedterminals:part_terminal_storage"
                        + " + ae2:terminal (first 3 of 4)",
                RecipeSummary.primary(List.of(
                        "minecraft:crafting_table", "minecraft:crafting_table",
                        "integratedterminals:part_terminal_storage",
                        "ae2:terminal", "ae2:crafting_terminal")));
    }
}
