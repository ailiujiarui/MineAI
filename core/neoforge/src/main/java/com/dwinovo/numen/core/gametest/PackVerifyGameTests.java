package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.verify.VerifyTool;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * {@code verify}:拿模型的宣称去量权威状态,回 {@code verified} 判词。
 *
 * <p>假完成是模型的默认毛病。这里钉三件事:背包里数得准(够 = true、不够 = false 并点名实际数量与物品);
 * 某一格方块对得上才对;kind 不认识时是干净的参数错,不是异常炸出游戏。
 *
 * <p>本工具还没进 {@code NumenCore} 的注册表(约束不许改那个文件),所以批次开场自己登记一次,已登记就跳过。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackVerifyGameTests {

    @BeforeBatch(batch = "numen_pack_verify")
    public static void prepareVerifyBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
        if (ToolRegistry.get("verify") == null) {
            ToolRegistry.register(new VerifyTool());
        }
    }

    /** 背包里 3 颗钻石:要 3 成立,要 5 不成立且报出实际的 3 与物品名。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_verify")
    public static void verify_have_counts_the_companion_inventory(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_verify_have", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 3));

        ToolRun enough = call(companion, "verify", args("kind", "have", "item", "diamond", "count", 3));
        ToolRun shortOf = call(companion, "verify", args("kind", "have", "item", "diamond", "count", 5));

        helper.succeedWhen(() -> {
            helper.assertTrue(enough.done() && shortOf.done(), "verify has not replied");
            helper.assertTrue(enough.succeeded() && verifiedOf(enough),
                    "3 diamonds did not verify: " + enough.reply());
            helper.assertTrue(!verifiedOf(shortOf),
                    "5 diamonds verified with only 3: " + shortOf.reply());
            String actual = dataOf(shortOf).get("actual").getAsString();
            helper.assertTrue(actual.contains("3") && actual.contains("diamond"),
                    "the actual count or item is not named: " + shortOf.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 那一格是石头:验石头成立,验泥土不成立并报出实际的石头。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_verify")
    public static void verify_block_checks_the_position(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_verify_block", new BlockPos(3, 2, 3), false);

        ToolRun present = call(companion, "verify", args("kind", "block", "block", "minecraft:stone",
                "x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));
        ToolRun wrong = call(companion, "verify", args("kind", "block", "block", "minecraft:dirt",
                "x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(present.done() && wrong.done(), "verify has not replied");
            helper.assertTrue(present.succeeded() && verifiedOf(present),
                    "stone did not verify: " + present.reply());
            helper.assertTrue(!verifiedOf(wrong),
                    "dirt verified where stone is: " + wrong.reply());
            helper.assertTrue(dataOf(wrong).get("actual").getAsString().contains("stone"),
                    "the actual block is not named: " + wrong.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** kind 不认识:干净的参数错(顶层失败),不把异常扔进游戏。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_verify")
    public static void verify_unknown_kind_fails_cleanly(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_verify_kind", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "verify", args("kind", "teleport", "block", "minecraft:stone"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "verify has not replied");
            helper.assertTrue(!run.succeeded(), "an unknown kind was accepted: " + run.reply());
            helper.assertTrue(run.reply().contains("invalid arguments"),
                    "not a clean argument failure: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    private static JsonObject dataOf(ToolRun run) {
        return JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
    }

    private static boolean verifiedOf(ToolRun run) {
        return dataOf(run).get("verified").getAsBoolean();
    }
}
