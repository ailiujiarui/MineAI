package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.kb.KnowledgeBase;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * {@code kb_query}:包内离线知识索引。书与任务文本就是包自己的 —— GuideME 指南、Patchouli 手册、
 * FTB Quests 任务文件;这里钉两件事:真出现在书里的词搜得到(至少一条命中),胡说八道得到一句干净的
 * "书里没有",而不是硬失败。
 *
 * <p>干净测试环境里一个指南/手册/任务文件都没有:这时第一条用例直接成功返回(跳过),第二条照样成立
 * (空索引也会回那句"没有")。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackKbGameTests {

    @BeforeBatch(batch = "numen_pack_kb")
    public static void prepareKbBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 从索引里取一个真出现过的词当探针,问 {@code kb_query}:至少一条命中。没书就跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_kb")
    public static void kb_query_finds_indexed_book_text(GameTestHelper helper) {
        String term = KnowledgeBase.get(helper.getLevel().getServer()).sampleTerm();
        if (term == null) {
            helper.succeed();   // 干净环境:包里没有任何书,跳过
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_kb", new BlockPos(4, 2, 4), false);
        ToolRun run = call(companion, "kb_query", args("query", term, "limit", 3));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "kb_query has not replied");
            helper.assertTrue(run.succeeded(), "kb_query refused: " + run.reply());
            helper.assertTrue(!run.reply().contains("nothing on"),
                    "an indexed term returned nothing: " + term + " — " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 胡说八道:{@code kb_query} 干净地说"书里没有",绝不硬失败。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_kb")
    public static void kb_query_nonsense_is_a_clean_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_kb_none", new BlockPos(4, 2, 4), false);
        ToolRun run = call(companion, "kb_query", args("query", "zzzqxwv_nonexistent_term_9182"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "kb_query has not replied");
            helper.assertTrue(run.succeeded(), "a nonsense query was a hard failure: " + run.reply());
            helper.assertTrue(run.reply().contains("nothing on"),
                    "the nothing reply is not clean: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
