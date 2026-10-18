package com.dwinovo.numen.gametest;

import com.dwinovo.numen.act.Interaction;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Sight;
import com.dwinovo.numen.tools.CraftOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 第一视角用攻击充能抬起手持物:非攻击动作只播挥手,真正攻击仍重置充能。 */
public class FirstPersonHandGameTests {
    private static final String BATCH = "numen_hand_visibility";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void repeated_block_use_keeps_the_first_person_hand_raised(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "hand_use", new BlockPos(4, 2, 4), false);
        body.getInventory().setItem(0, new ItemStack(Items.STONE_AXE));
        BlockPos lever = helper.absolutePos(new BlockPos(6, 2, 4));
        helper.getLevel().setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        // 照她按右键的做法对准星:看向拉杆看得见的那一面,再拾取一次——拉杆的轮廓只有小半格高,看格心会从它上面擦过去
        Sight.Trace seen = body.look().use(lever);
        helper.assertTrue(seen != null, "fixture: the lever has no face in sight from where she stands");
        body.look().at(seen.point());
        HitResult aimed = body.mouse().pick();
        helper.assertTrue(aimed instanceof BlockHitResult taken && taken.getBlockPos().equals(lever),
                "fixture: the lever is not under her crosshair: " + aimed);
        BlockHitResult hit = (BlockHitResult) aimed;
        // 两次右键至少隔 4 刻(鼠标自己的缓手),所以每 4 刻按一次
        for (int tick = 40; tick <= 52; tick += 4) helper.runAtTickTime(tick, () -> {
            boolean powered = helper.getLevel().getBlockState(lever).getValue(LeverBlock.POWERED);
            Interaction.useBlock(body, hit).tick();
            helper.assertTrue(helper.getLevel().getBlockState(lever).getValue(LeverBlock.POWERED) != powered,
                    "the block was not actually used");
            helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "right-click lowered the first-person hand");
        });
        helper.runAtTickTime(56, () -> {
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
            helper.succeed();
        });
    }

    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void repeated_item_fallthrough_keeps_the_first_person_hand_raised(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "hand_throw", new BlockPos(4, 2, 4), false);
        body.getInventory().setItem(0, new ItemStack(Items.SNOWBALL, 16));
        BlockPos stone = helper.absolutePos(new BlockPos(6, 2, 4));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(stone), Direction.WEST, stone, false);
        for (int tick = 40; tick <= 68; tick += 4) helper.runAtTickTime(tick, () -> {
            int before = body.getMainHandItem().getCount();
            Interaction.forHit(body, hit, Interaction.Button.USE, 0, true, false).tick();
            helper.assertTrue(body.getMainHandItem().getCount() == before - 1, "the item fallback did not throw a snowball");
            helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "item self-use lowered the first-person hand");
        });
        helper.runAtTickTime(72, () -> {
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
            helper.succeed();
        });
    }

    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void repeated_crafting_keeps_the_first_person_hand_raised(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "hand_craft", new BlockPos(4, 2, 4), false);
        body.getInventory().setItem(0, new ItemStack(Items.STONE_AXE));
        body.getInventory().setItem(9, new ItemStack(Items.OAK_LOG, 8));
        for (int tick = 40; tick < 44; tick++) helper.runAtTickTime(tick, () -> {
            var crafted = CraftOps.craft(ResourceLocation.withDefaultNamespace("oak_planks"), 4, body);
            helper.assertTrue(crafted.crafted() == 4, "the recipe was not actually crafted");
            helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "crafting lowered the first-person hand");
        });
        helper.runAtTickTime(44, () -> {
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
            helper.succeed();
        });
    }

    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void native_building_keeps_the_first_person_hand_raised(GameTestHelper helper) {
        building(helper, Items.STONE_BRICKS, "stone_bricks");
    }

    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void stateful_building_keeps_the_first_person_hand_raised(GameTestHelper helper) {
        building(helper, Items.OAK_STAIRS, "oak_stairs[facing=east]");
    }

    private static void building(GameTestHelper helper, Item item, String state) {
        NumenPlayer body = spawnAt(helper, "hand_build", new BlockPos(4, 2, 4), false);
        body.getInventory().setItem(0, new ItemStack(item, 16));
        BlockPos at = helper.absolutePos(new BlockPos(5, 2, 4));
        ToolRun[] run = {null};
        helper.runAtTickTime(40, () -> run[0] = lua(body, "numen.build.place(numen.shape.layer(" + xyz(at)
                + ", {\"###\"}, {[\"#\"] = \"" + state + "\"}))"));
        helper.onEachTick(() -> {
            if (run[0] == null) return;
            helper.assertTrue(body.getAttackStrengthScale(0F) == 1F, "building animation lowered the first-person hand");
            if (!run[0].done()) return;
            helper.assertTrue(run[0].succeeded(), "building failed: " + run[0].outcome());
            for (int x = 0; x < 3; x++) helper.assertTrue(helper.getLevel().getBlockState(at.offset(x, 0, 0)).getBlock().asItem() == item,
                    "the building action did not place every block");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
            helper.succeed();
        });
    }

    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void an_actual_attack_still_resets_the_charge(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "hand_attack", new BlockPos(4, 2, 4), false);
        body.getInventory().setItem(0, new ItemStack(Items.STONE_AXE));
        var pig = EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(5, 2, 4));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        helper.runAtTickTime(40, () -> {
            Interaction.attackEntity(body, pig).tick();
            helper.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the entity was not actually attacked");
            helper.assertTrue(body.getAttackStrengthScale(0F) == 0F, "an actual attack did not reset its cooldown");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
            helper.succeed();
        });
    }
}
