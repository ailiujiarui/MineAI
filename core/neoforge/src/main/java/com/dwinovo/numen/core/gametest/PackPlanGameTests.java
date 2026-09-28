package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonElement;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * {@code plan_make}:把任务书要的东西接到纯 JVM 规划层——只出计划、不动手。这里钉两件事:
 * 拿着原料能吐出"还差什么 + 按什么顺序做"的产线(木板、木棍),以及手里已经有了就直接说不用做。
 *
 * <p>本用例只测规划工具本身,不经过模型,也不真去合成:回执里认的是工具交给模型的那段话。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackPlanGameTests {

    @BeforeBatch(batch = "numen_pack_plan")
    public static void preparePlanBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 整合包专属物品在干净环境里不存在:不存在就跳过,不判红。 */
    private static boolean itemAbsent(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(net.minecraft.resources.ResourceLocation.parse(id)).isEmpty();
    }

    /** 只带一根橡木原木,要一把木镐:计划该点出木板与木棍这两步,并说清这根原木不够。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_pack_plan")
    public static void plan_make_names_planks_and_sticks_for_a_wooden_pickaxe(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_plan_pickaxe", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.OAK_LOG, 1));
        ToolRun run = call(companion, "plan_make", args(
                "item_id", "minecraft:wooden_pickaxe", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "plan_make has not replied");
            helper.assertTrue(run.succeeded(), "plan_make refused: " + run.reply());
            String reply = run.reply();
            helper.assertTrue(reply != null && reply.contains("oak_planks") && reply.contains("stick"),
                    "the plan does not name planks and sticks: " + reply);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 压缩方块 {@code allthecompressed:iron_block_8x} 的压缩/解压两条配方互相指向(8x 解 9x、
     * 9x 压 8x),规划层不该硬失败:把成环处当采集叶子,给出部分计划,并在 {@code unresolved}
     * 里点名这个方块。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_pack_plan")
    public static void plan_make_returns_a_partial_plan_for_a_cyclic_compressed_target(GameTestHelper helper) {
        if (itemAbsent("allthecompressed:iron_block_8x")) { helper.succeed(); return; }
        NumenPlayer companion = spawnAt(helper, "gametest_plan_cycle", new BlockPos(4, 2, 4), false);
        ToolRun run = call(companion, "plan_make", args(
                "item_id", "allthecompressed:iron_block_8x", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "plan_make has not replied");
            helper.assertTrue(run.succeeded(), "plan_make should return a partial plan, not hard-fail: " + run.reply());
            JsonObject data = JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
            helper.assertTrue(data != null && data.has("unresolved"),
                    "a partial plan must name the cyclic node(s): " + run.reply());
            boolean namesCycle = false;
            for (JsonElement node : data.getAsJsonArray("unresolved")) {
                if (node.getAsString().equals("allthecompressed:iron_block_8x")) {
                    namesCycle = true;
                }
            }
            helper.assertTrue(namesCycle, "unresolved should name the compressed node: " + run.reply());
            helper.assertTrue(data.getAsJsonArray("steps").size() > 0,
                    "a partial plan should still carry the reachable steps: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 真正报错的目标 {@code jdte:mineral_survey}:它的依赖里夹着压缩方块,成环不该再让整条计划失败。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_pack_plan")
    public static void plan_make_does_not_hard_fail_on_a_target_whose_dependency_cycles(GameTestHelper helper) {
        if (itemAbsent("jdte:mineral_survey")) { helper.succeed(); return; }
        NumenPlayer companion = spawnAt(helper, "gametest_plan_survey", new BlockPos(4, 2, 4), false);
        ToolRun run = call(companion, "plan_make", args(
                "item_id", "jdte:mineral_survey", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "plan_make has not replied");
            helper.assertTrue(run.succeeded(),
                    "a dependency cycle should yield a partial plan, not a hard fail: " + run.reply());
            helper.assertTrue(run.reply() != null && run.reply().contains("mineral_survey"),
                    "the plan should still name the target: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 手里已经有一把木镐:什么都不用做,计划如实说已经够了。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_pack_plan")
    public static void plan_make_says_an_already_held_item_is_satisfied(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_plan_held", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE, 1));
        ToolRun run = call(companion, "plan_make", args(
                "item_id", "minecraft:wooden_pickaxe", "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "plan_make has not replied");
            helper.assertTrue(run.succeeded(), "plan_make failed: " + run.reply());
            String reply = run.reply();
            helper.assertTrue(reply != null && reply.toLowerCase(java.util.Locale.ROOT).contains("satisfied"),
                    "the plan does not report the item is already held: " + reply);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
