package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
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
