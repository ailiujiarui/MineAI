package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 模组整合包能力阶梯(MineAI 方法论:摆题面 → 走她自己的工具链 → 读权威背包判定)。
 *
 * <p>题目里没有目标模组的编译依赖:物品按名字查注册表,配方从配方本当场展开——给的原料是配方
 * 真实要的那几样,不是写死的猜测。目标模组不在场时直接成功返回(跳过),没装它的环境不会因此变红。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class MekGameTests {

    @BeforeBatch(batch = "numen_mek")
    public static void prepareMekBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** L9:给足材料,她自己合成一个 Mekanism 钢制机壳。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_mek")
    public static void l9_crafts_a_steel_casing(GameTestHelper helper) {
        craftFromRecipe(helper, "gametest_steelsmith", "mekanism:steel_casing");
    }

    /** L10:给足材料,她自己合成一台 Mekanism Generators 风力发电机。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_mek")
    public static void l10_crafts_a_wind_generator(GameTestHelper helper) {
        craftFromRecipe(helper, "gametest_windwright", "mekanismgenerators:wind_generator");
    }

    /**
     * 摆好题面——把配方真实要的原料一次性给足——然后走她的 {@code craft} 工具,读权威背包判定。
     * 目标物品不存在(模组没装)就成功返回,当作跳过。
     */
    private static void craftFromRecipe(GameTestHelper helper, String companionName, String itemId) {
        ServerLevel level = helper.getLevel();
        Item target = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(Items.AIR);
        if (target == Items.AIR) {
            helper.succeed();
            return;
        }
        CraftingRecipe recipe = craftingRecipeFor(level, target);
        if (recipe == null) {
            helper.fail("no crafting recipe produces " + itemId);
            return;
        }

        NumenPlayer companion = spawnAt(helper, companionName, new BlockPos(4, 2, 4), false);
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0) {
                companion.getInventory().add(new ItemStack(options[0].getItem(), 64));
            }
        }
        // 3x3 配方要有工作台够得着;放一个在她旁边(2x2 配方用不上,无害)
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 4)),
                Blocks.CRAFTING_TABLE.defaultBlockState());

        ToolRun run = call(companion, "craft", args("item_id", itemId, "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "craft refused: " + run.reply());
            helper.assertTrue(companion.getInventory().contains(new ItemStack(target)),
                    "no " + itemId + " in the inventory after crafting");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 找出产出这个物品的合成配方(配方本当场展开,不写死原料)。 */
    private static CraftingRecipe craftingRecipeFor(ServerLevel level, Item target) {
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (holder.value().getResultItem(level.registryAccess()).is(target)) {
                return holder.value();
            }
        }
        return null;
    }
}
