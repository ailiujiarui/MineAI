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
 * 模组整合包能力阶梯的 <b>L9</b>:给足材料,让她自己合成一个 Mekanism 钢制机壳。
 *
 * <p>跟着 MineAI 阶梯靶场的方法论:摆好题面(给材料)→ 走她自己的工具链({@code craft})→
 * 读权威背包判定成败(不看模型的话)。阶梯前面几级的产物在 L9 一次性给足,所以她从"合成"这一步算起。
 *
 * <p>题目里没有 Mekanism 的编译依赖:目标物品按名字查注册表,配方从配方本当场展开——给的原料是
 * 配方真实要的那几样,不是写死的猜测。Mekanism 不在场时直接成功返回(跳过),没装它的环境不会因此变红。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class MekGameTests {

    private static final ResourceLocation STEEL_CASING = ResourceLocation.parse("mekanism:steel_casing");

    @BeforeBatch(batch = "numen_mek")
    public static void prepareMekBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_mek")
    public static void l9_crafts_a_steel_casing_from_provided_materials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item casing = BuiltInRegistries.ITEM.getOptional(STEEL_CASING).orElse(Items.AIR);
        if (casing == Items.AIR) {
            helper.succeed();   // 没装 Mekanism:跳过,不算失败
            return;
        }

        CraftingRecipe recipe = craftingRecipeFor(level, casing);
        if (recipe == null) {
            helper.fail("no crafting recipe produces " + STEEL_CASING);
            return;
        }

        NumenPlayer companion = spawnAt(helper, "gametest_steelsmith", new BlockPos(4, 2, 4), false);
        // L9 的题面:把配方真实要的原料一次性给足
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0) {
                companion.getInventory().add(new ItemStack(options[0].getItem(), 64));
            }
        }
        // 3x3 配方要有工作台够得着;放一个在她旁边(2x2 配方用不上,无害)
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(5, 2, 4)), Blocks.CRAFTING_TABLE.defaultBlockState());

        ToolRun run = call(companion, "craft", args("item_id", STEEL_CASING.toString(), "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "craft refused: " + run.reply());
            helper.assertTrue(companion.getInventory().contains(new ItemStack(casing)),
                    "no " + STEEL_CASING + " in the inventory after crafting");
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
