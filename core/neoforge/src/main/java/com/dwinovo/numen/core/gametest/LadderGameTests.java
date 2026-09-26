package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * MineAI 能力阶梯 L1-L8(Numen 版):每一级都是"拿到某件东西",摆好题面 → 走她自己的工具链 → 读权威背包判定。
 *
 * <ul>
 *   <li>L1-L2 / L5:放资源块再走 {@code mine}(异步任务);</li>
 *   <li>L3-L4 / L6 / L8:给材料再走 {@code craft}(同步);</li>
 *   <li>L7:熔炼不是合成——走 {@code interact_at} 开熔炉 + {@code transfer} 放料/取料。</li>
 * </ul>
 *
 * <p>L8 需要 Mekanism;不在场就跳过(与 L9/L10 同一口径)。原清单见 MineAI 的 {@code tools/task-harness.ps1}。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class LadderGameTests {

    @BeforeBatch(batch = "numen_ladder")
    public static void prepareLadderBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** L1:挖身边草方块,拿到 1 个泥土。 */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_ladder")
    public static void l1_mine_dirt(GameTestHelper helper) {
        mineBlocks(helper, "gametest_l1", Blocks.GRASS_BLOCK.defaultBlockState(),
                List.of(new BlockPos(5, 2, 4)), "minecraft:grass_block", 1, Items.DIRT, 1);
    }

    /** L2:砍 4 个橡木原木。 */
    @GameTest(template = "floor16", timeoutTicks = 900, batch = "numen_ladder")
    public static void l2_chop_four_oak_logs(GameTestHelper helper) {
        mineBlocks(helper, "gametest_l2", Blocks.OAK_LOG.defaultBlockState(),
                List.of(new BlockPos(5, 2, 4), new BlockPos(5, 3, 4), new BlockPos(5, 4, 4), new BlockPos(5, 5, 4)),
                "minecraft:oak_log", 4, Items.OAK_LOG, 4);
    }

    /** L3:用木板做一个工作台。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_ladder")
    public static void l3_craft_a_crafting_table(GameTestHelper helper) {
        craftFromMaterials(helper, "gametest_l3", "minecraft:crafting_table",
                Map.of(Items.OAK_PLANKS, 4), Items.CRAFTING_TABLE);
    }

    /** L4:做一把木镐。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_ladder")
    public static void l4_craft_a_wooden_pickaxe(GameTestHelper helper) {
        craftFromMaterials(helper, "gametest_l4", "minecraft:wooden_pickaxe",
                Map.of(Items.OAK_PLANKS, 3, Items.STICK, 2), Items.WOODEN_PICKAXE);
    }

    /** L5:拿木镐挖 3 块石头,得到 3 个圆石。 */
    @GameTest(template = "floor16", timeoutTicks = 900, batch = "numen_ladder")
    public static void l5_mine_three_cobblestone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> stones = List.of(
                new BlockPos(5, 2, 4), new BlockPos(5, 3, 4), new BlockPos(5, 4, 4));
        for (BlockPos pos : stones) {
            level.setBlockAndUpdate(helper.absolutePos(pos), Blocks.STONE.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_l5", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        TaskRecord task = call(companion, "mine", args(
                "block_ids", List.of("minecraft:stone"), "count", 3)).task();
        helper.succeedWhen(() -> {
            helper.assertTrue(task.getResult() != null && task.getResult().success(),
                    "mine failed: " + outcome(task));
            helper.assertTrue(PlayerInv.count(companion.getInventory(), Items.COBBLESTONE) >= 3,
                    "fewer than 3 cobblestone collected");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** L6:用 8 个圆石做一个熔炉。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_ladder")
    public static void l6_craft_a_furnace(GameTestHelper helper) {
        craftFromMaterials(helper, "gametest_l6", "minecraft:furnace",
                Map.of(Items.COBBLESTONE, 8), Items.FURNACE);
    }

    /** L7:把一块粗铁放进熔炉熔成铁锭。熔炼不走 craft——开熔炉、放料、等、取料。 */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = "numen_ladder")
    public static void l7_smelt_an_iron_ingot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos furnace = helper.absolutePos(new BlockPos(5, 2, 4));
        level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_l7", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.RAW_IRON, 1));
        companion.getInventory().add(new ItemStack(Items.COAL, 1));

        helper.startSequence()
                .thenExecute(() -> call(companion, "interact_at", args(
                        "button", "right",
                        "x", furnace.getX(), "y", furnace.getY(), "z", furnace.getZ(),
                        "hold_ticks", 0)))
                .thenWaitUntil(() -> helper.assertTrue(
                        companion.containerMenu instanceof AbstractFurnaceMenu, "furnace GUI not open"))
                .thenExecute(() -> {
                    // 熔炉菜单:0=输入,1=燃料,2=产出。她自己的格子按菜单号取,不靠固定偏移。
                    int raw = menuSlotOf(companion, Items.RAW_IRON);
                    int coal = menuSlotOf(companion, Items.COAL);
                    call(companion, "transfer", args("moves", List.of(
                            move(raw, 0, 1), move(coal, 1, 1))));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        companion.containerMenu.getSlot(0).getItem().is(Items.RAW_IRON)
                                && companion.containerMenu.getSlot(1).getItem().is(Items.COAL),
                        "the raw iron and coal did not reach the furnace"))
                .thenWaitUntil(() -> helper.assertTrue(
                        companion.containerMenu.getSlot(2).getItem().is(Items.IRON_INGOT),
                        "the furnace has not produced the iron ingot yet"))
                // 产物在产出格,还没进包:整叠路由取出(省 to),才拿得到手里。
                .thenExecute(() -> call(companion, "transfer", args("moves", List.of(args("from", 2)))))
                .thenWaitUntil(() -> helper.assertTrue(
                        PlayerInv.count(companion.getInventory(), Items.IRON_INGOT) >= 1, "no iron ingot yet"))
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /** L8:从零合成一个 Mekanism 冶金灌注机(按真实配方给料)。Mekanism 不在场则跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_ladder")
    public static void l8_craft_a_metallurgic_infuser(GameTestHelper helper) {
        craftFromRecipe(helper, "gametest_l8", "mekanism:metallurgic_infuser");
    }

    // ---- helpers ----

    /** 放一批方块,让她 `mine` 到指定的掉落物。 */
    private static void mineBlocks(GameTestHelper helper, String name, BlockState state, List<BlockPos> blocks,
                                   String blockId, int count, Item expected, int min) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : blocks) {
            level.setBlockAndUpdate(helper.absolutePos(pos), state);
        }
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(4, 2, 4), false);
        TaskRecord task = call(companion, "mine", args(
                "block_ids", List.of(blockId), "count", count)).task();
        helper.succeedWhen(() -> {
            helper.assertTrue(task.getResult() != null && task.getResult().success(),
                    "mine failed: " + outcome(task));
            helper.assertTrue(PlayerInv.count(companion.getInventory(), expected) >= min,
                    "collected fewer than " + min + " of the expected drop");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 给足材料,让她 `craft` 一件东西。 */
    private static void craftFromMaterials(GameTestHelper helper, String name, String itemId,
                                           Map<Item, Integer> materials, Item expected) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(4, 2, 4), false);
        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            companion.getInventory().add(new ItemStack(entry.getKey(), entry.getValue()));
        }
        // 3x3 配方要有工作台够得着;放一个在她旁边(2x2 配方用不上,无害)
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 4)),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        ToolRun run = call(companion, "craft", args("item_id", itemId, "count", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "craft refused: " + run.reply());
            helper.assertTrue(PlayerInv.count(companion.getInventory(), expected) >= 1, "no " + itemId);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 按配方本给足原料再 `craft`;目标物品不存在(模组没装)则跳过。 */
    private static void craftFromRecipe(GameTestHelper helper, String name, String itemId) {
        ServerLevel level = helper.getLevel();
        Item target = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(Items.AIR);
        if (target == Items.AIR) {
            helper.succeed();   // 模组没装:跳过
            return;
        }
        CraftingRecipe recipe = craftingRecipeFor(level, target);
        if (recipe == null) {
            helper.fail("no crafting recipe produces " + itemId);
            return;
        }
        NumenPlayer companion = spawnAt(helper, name, new BlockPos(4, 2, 4), false);
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0) {
                companion.getInventory().add(new ItemStack(options[0].getItem(), 64));
            }
        }
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 4)),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        ToolRun run = call(companion, "craft", args("item_id", itemId, "count", 1));
        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "craft refused: " + run.reply());
            helper.assertTrue(PlayerInv.count(companion.getInventory(), target) >= 1, "no " + itemId);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    private static CraftingRecipe craftingRecipeFor(ServerLevel level, Item target) {
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (holder.value().getResultItem(level.registryAccess()).is(target)) {
                return holder.value();
            }
        }
        return null;
    }

    private static com.google.gson.JsonObject move(int from, int to, int count) {
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("from", from);
        m.addProperty("to", to);
        m.addProperty("count", count);
        return m;
    }

    private static String outcome(TaskRecord task) {
        return task.getResult() == null ? "no result" : task.getResult().message();
    }
}
