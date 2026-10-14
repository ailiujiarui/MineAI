package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.kaleidoscope.Cooker;
import com.dwinovo.numen.plugins.kaleidoscope.Dish;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 目标模组在场时,用真实炒锅和汤锅验证从投料到出锅的非攻击挥手。 */
@GameTestHolder(Constants.MOD_ID)
public class KaleidoscopeHandGameTests {
    private static final String BATCH = "numen_hand_cooking";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @GameTestGenerator
    public static List<TestFunction> cookingHands() {
        if (!ModList.get().isLoaded("kaleidoscope_cookery")) return List.of();
        return List.of(
                new TestFunction(BATCH, "stir_frying_keeps_the_first_person_hand_raised", "numen:floor16",
                        600, 0, true, Cases::pot),
                new TestFunction(BATCH, "soup_cooking_keeps_the_first_person_hand_raised", "numen:floor16",
                        600, 0, true, Cases::stockpot));
    }

    /** 联动类只在目标模组在场时链接;普通 GameTest 运行不加载它们。 */
    private static final class Cases {
        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", path);
        }

        private static Cooker cookware(GameTestHelper helper, String block) {
            BlockPos at = helper.absolutePos(new BlockPos(6, 3, 4));
            helper.getLevel().setBlockAndUpdate(at.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(at, BuiltInRegistries.BLOCK.get(id(block)).defaultBlockState());
            Cooker cooker = Cooker.at(helper.getLevel(), at);
            helper.assertTrue(cooker != null && cooker.report().hasHeatSource(), "the actual cookware has no heat");
            return cooker;
        }

        private static void step(GameTestHelper helper, NumenPlayer body, Cooker.Step step) {
            helper.assertTrue(step.kind() != Cooker.Step.Kind.BLOCKED, "cooking was blocked: " + step.note());
            helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "cooking animation lowered the first-person hand: " + step.note());
        }

        private static void pot(GameTestHelper helper) {
            NumenPlayer body = spawnAt(helper, "hand_stir", new BlockPos(4, 2, 4), false);
            body.getInventory().setItem(0, new ItemStack(Items.STONE_AXE));
            body.getInventory().setItem(9, new ItemStack(BuiltInRegistries.ITEM.get(id("oil"))));
            body.getInventory().setItem(10, new ItemStack(BuiltInRegistries.ITEM.get(id("kitchen_shovel"))));
            body.getInventory().setItem(11, new ItemStack(Items.BEEF, 3));
            body.getInventory().setItem(12, new ItemStack(Items.BOWL, 8));
            Cooker cooker = cookware(helper, "pot");
            Dish dish = Dish.byId(helper.getLevel(), id("pot/beef_to_cooked_beef_3"));
            helper.assertTrue(dish != null, "the actual pot recipe is missing");
            boolean[] started = {false};
            helper.runAtTickTime(40, () -> {
                step(helper, body, cooker.oil(body));
                for (int i = 0; i < 3; i++) step(helper, body, cooker.fill(body, dish, dish.portions(helper.getLevel())));
                helper.assertTrue(cooker.report().inThePot().size() == 3, "the ingredients did not enter the pot");
                step(helper, body, cooker.stir(body));
                started[0] = true;
            });
            helper.onEachTick(() -> {
                if (!started[0]) return;
                Cooker.Step stirring = cooker.stir(body);
                step(helper, body, stirring);
                if (stirring.kind() != Cooker.Step.Kind.DONE) return;
                Cooker.Step plated = cooker.plate(body, dish);
                step(helper, body, plated);
                if (plated.kind() == Cooker.Step.Kind.WORKING) return;
                helper.assertTrue(plated.kind() == Cooker.Step.Kind.DONE && plated.plated().is(Items.COOKED_BEEF),
                        "the actual pot did not produce cooked beef: " + plated.note());
                CompanionFactory.despawn(helper.getLevel().getServer(), body);
                helper.succeed();
            });
        }

        private static void stockpot(GameTestHelper helper) {
            NumenPlayer body = spawnAt(helper, "hand_soup", new BlockPos(4, 2, 4), false);
            body.getInventory().setItem(0, new ItemStack(Items.STONE_AXE));
            body.getInventory().setItem(9, new ItemStack(Items.WATER_BUCKET));
            body.getInventory().setItem(10, new ItemStack(Items.BONE, 7));
            body.getInventory().setItem(11, new ItemStack(BuiltInRegistries.ITEM.get(id("stockpot_lid"))));
            body.getInventory().setItem(12, new ItemStack(Items.BOWL, 8));
            Cooker cooker = cookware(helper, "stockpot");
            Dish dish = Dish.byId(helper.getLevel(), id("stockpot/pork_bone_soup"));
            helper.assertTrue(dish != null, "the actual stockpot recipe is missing");
            boolean[] started = {false};
            helper.runAtTickTime(40, () -> {
                step(helper, body, cooker.base(body, dish));
                for (int i = 0; i < 7; i++) step(helper, body, cooker.fill(body, dish, dish.portions(helper.getLevel())));
                helper.assertTrue(cooker.report().inThePot().size() == 7, "the ingredients did not enter the stockpot");
                step(helper, body, cooker.lid(body));
                step(helper, body, cooker.lid(body));
                helper.assertTrue(!cooker.report().hasLid().orElseThrow(), "the lid was not taken off");
                step(helper, body, cooker.lid(body));
                // 揭盖会临时换到空手接盖子;同刻换回原工具,避免把正常换装备的收手当成动画故障。
                body.getInventory().selected = 0;
                started[0] = true;
            });
            helper.onEachTick(() -> {
                if (!started[0]) return;
                helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "soup cooking lowered the first-person hand");
                if (!cooker.report().stage().equals("finished")) return;
                step(helper, body, cooker.lid(body));
                Cooker.Step plated = cooker.plate(body, dish);
                step(helper, body, plated);
                helper.assertTrue(plated.kind() == Cooker.Step.Kind.DONE && !plated.plated().isEmpty(),
                        "the actual stockpot did not hand over the soup: " + plated.note());
                CompanionFactory.despawn(helper.getLevel().getServer(), body);
                helper.succeed();
            });
        }
    }
}
