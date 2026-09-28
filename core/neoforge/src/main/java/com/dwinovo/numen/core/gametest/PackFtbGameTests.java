package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * FTB Quests 联动的 {@code claim}:一个没有任何可领奖励(甚至没有队伍)的同伴调它,必须干净地回一句失败,
 * 不抛异常、不把半截面留在世界里。
 *
 * <p>为什么钉这个而不是钉一次真的领奖:无头 gameTestServer 的类路径上没有 FTB Quests——这个联动模块只
 * {@code compileOnly} 依赖它,运行到哪台服务器由服主装的那份决定。所以这里不引用 FTB 的类,只经 {@code command}
 * 工具敲 {@code numen ftbquests claim},按 FTB 在不在场分支断言:
 * <ul>
 *   <li>FTB 在场:她的来源已经过 {@code command(numen)} 放行规则,命令解析得通;她没有 FTB 队伍,
 *       回执该是一句说清"没有队伍、没得领"的失败。</li>
 *   <li>FTB 不在场:整个 {@code ftbquests} 命令组都没登记,这一行解析不通,回执是"写不通"的失败,同样不抛。</li>
 * </ul>
 *
 * <p><b>没能测到的</b>:真的从 FTB 的队伍/任务里领出一件奖励、以及它进背包的增量——那要先在运行时造出 FTB 队伍
 * 与任务,而 FTB 不在本测试运行路径上;这些留给装了 FTB 的世界里的人手验证。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackFtbGameTests {

    @BeforeBatch(batch = "numen_pack_ftb")
    public static void prepareFtbBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 没有可领的东西时 {@code numen ftbquests claim} 干净失败:有回话、说得清、不抛异常。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_ftb")
    public static void claim_with_nothing_to_claim_reports_cleanly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean ftb = ModList.get().isLoaded("ftbquests");
        Constants.LOG.info("[numen-ftb] ftbquests installed = {}; a real claim through a runtime FTB team/quest "
                + "is not driven from this headless test", ftb);
        NumenPlayer companion = spawnAt(helper, "gametest_ftb_claim", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_ftb_host");
        PermissionStore.of(level.getServer(), owner.getUUID())
                .add(Verdict.Kind.ALLOW, Rule.parse("command(numen)"));
        ToolRun run = command(companion, "numen ftbquests claim");

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "numen ftbquests claim did not come back: " + run.reply());
            JsonObject reply = JsonParser.parseString(run.reply()).getAsJsonObject();
            helper.assertTrue(reply.has("message") && !reply.get("message").getAsString().isBlank(),
                    "the reply has no message: " + run.reply());
            helper.assertTrue(!reply.get("success").getAsBoolean(),
                    (ftb ? "the companion has no FTB team, so the claim must fail cleanly"
                            : "without FTB Quests the line must fail cleanly") + ": " + run.reply());
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }
}
