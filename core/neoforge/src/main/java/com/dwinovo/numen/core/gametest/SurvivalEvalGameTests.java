package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.eval.SurvivalWorldObserver;
import com.dwinovo.numen.core.nav.CompanionHands;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 评分器的世界夹具测试,不调用模型,也不作为智能体生存能力基线。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SurvivalEvalGameTests {
    private static final String BATCH = "numen_survival_eval";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, NOON);
    }

    @GameTest(template = "floor20", timeoutTicks = 300, batch = BATCH)
    public static void sustainable_fixture_reports_auditable_world_facts(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_home", new BlockPos(5, 2, 5), false);
        room(helper, true);
        farm(helper);
        helper.setBlock(new BlockPos(11, 2, 5), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(12, 2, 5), Blocks.FURNACE);
        body.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        body.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        body.getInventory().add(new ItemStack(Items.BREAD, 4));
        SurvivalWorldObserver observer = new SurvivalWorldObserver();
        for (int x = 10; x < 13; x++) {
            harvestAndPlant(helper, observer, body, new BlockPos(x, 2, 10), 7, body.getUUID());
        }
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            drop(helper, body, new BlockPos(5, 2, 5));
            JsonObject facts = observer.observe(body);
            helper.assertTrue(facts.get("usable_pickaxe").getAsBoolean(), "stone pickaxe must qualify");
            helper.assertTrue(facts.get("usable_hoe").getAsBoolean(), "wooden hoe must qualify");
            helper.assertTrue(facts.get("crafting_tables").getAsInt() >= 1, "placed crafting table missing");
            helper.assertTrue(facts.get("furnaces").getAsInt() >= 1, "placed furnace missing");
            helper.assertTrue(facts.get("growing_wheat").getAsInt() >= 8, "irrigated lit crop missing: " + facts);
            helper.assertTrue(facts.get("replanted_wheat").getAsInt() == 3, "three distinct replanted cells required");
            helper.assertTrue(facts.get("food_nutrition").getAsInt() == 20, "four bread supply 20 nutrition");
            helper.assertTrue(facts.get("shelter").getAsBoolean(), "enclosed lit home rejected: " + facts);
            helper.assertTrue(facts.getAsJsonObject("evidence").getAsJsonArray("wheat").size() >= 8,
                    "crop coordinates and state must remain auditable");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 300, batch = BATCH)
    public static void daylight_open_door_and_sealed_box_are_not_shelter(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_shelter", new BlockPos(5, 2, 5), false);
        room(helper, true);
        SurvivalWorldObserver observer = new SurvivalWorldObserver();
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            drop(helper, body, new BlockPos(5, 2, 5));
            helper.assertTrue(observer.observe(body).get("shelter").getAsBoolean(), "closed lit room should qualify");
            setDoor(helper, true);
            helper.assertTrue(!observer.observe(body).get("shelter").getAsBoolean(), "opening a door must immediately invalidate the same observer's shelter fact");
            setDoor(helper, false);
            helper.assertTrue(observer.observe(body).get("shelter").getAsBoolean(), "closing the door must immediately restore shelter evidence");
            helper.setBlock(new BlockPos(8, 2, 5), Blocks.STONE);
            helper.setBlock(new BlockPos(8, 3, 5), Blocks.STONE);
            helper.assertTrue(!observer.observe(body).get("shelter").getAsBoolean(), "sealed box needs a usable exit");
            setDoor(helper, false);
            helper.setBlock(new BlockPos(5, 5, 5), Blocks.STONE);
        }).thenIdle(30).thenExecute(() -> {
            helper.assertTrue(!observer.observe(body).get("shelter").getAsBoolean(), "daylight cannot substitute for interior block light");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 300, batch = BATCH)
    public static void walking_out_invalidates_cached_shelter_immediately(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_exit", new BlockPos(5, 2, 5), false);
        room(helper, true);
        SurvivalWorldObserver observer = new SurvivalWorldObserver();
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            drop(helper, body, new BlockPos(5, 2, 5));
            helper.assertTrue(observer.observe(body).get("shelter").getAsBoolean(), "initial lit room should qualify");
            drop(helper, body, new BlockPos(10, 2, 5));
            helper.assertTrue(!observer.observe(body).get("shelter").getAsBoolean(), "old location must not count after leaving home");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 300, batch = BATCH)
    public static void a_corner_door_without_an_inside_approach_is_not_an_exit(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_corner_door", new BlockPos(5, 2, 5), false);
        room(helper, true);
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(8, 3, 5), Blocks.STONE);
        BlockPos corner = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        helper.getLevel().setBlock(corner, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        helper.getLevel().setBlock(corner.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            drop(helper, body, new BlockPos(5, 2, 5));
            helper.assertTrue(helper.getLevel().getBlockState(corner).is(Blocks.OAK_DOOR),
                    "the corner-door counterexample must contain a real door");
            helper.assertTrue(!new SurvivalWorldObserver().observe(body).get("shelter").getAsBoolean(),
                    "an outdoor-facing corner door backed by an interior wall cannot provide an exit");
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void moving_one_block_refreshes_crop_and_workstation_range_immediately(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_range", new BlockPos(5, 2, 5), false);
        helper.setBlock(new BlockPos(29, 2, 5), Blocks.CRAFTING_TABLE);
        helper.setBlock(new BlockPos(29, 2, 6), Blocks.FURNACE);
        helper.setBlock(new BlockPos(29, 1, 8), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(new BlockPos(30, 1, 8), Blocks.WATER);
        helper.setBlock(new BlockPos(29, 2, 8), Blocks.WHEAT);
        SurvivalWorldObserver observer = new SurvivalWorldObserver();
        drop(helper, body, new BlockPos(5, 2, 5));
        JsonObject before = observer.observe(body);
        helper.assertTrue(before.get("growing_wheat").getAsInt() >= 1, "the boundary crop must be a valid initial fixture");
        helper.assertTrue(before.get("crafting_tables").getAsInt() >= 1 && before.get("furnaces").getAsInt() >= 1,
                "the boundary workstations must be visible initially");
        drop(helper, body, new BlockPos(4, 2, 5));
        JsonObject after = observer.observe(body);
        for (String fact : List.of("growing_wheat", "crafting_tables", "furnaces")) {
            helper.assertTrue(after.get(fact).getAsInt() == before.get(fact).getAsInt() - 1,
                    "one-block movement must immediately exclude the former 24-block boundary: " + fact);
        }
        helper.assertTrue(after.getAsJsonObject("evidence").get("scanned_game_tick").getAsLong()
                        == helper.getLevel().getGameTime(), "the evidence must carry this observation's game tick");
        CompanionFactory.despawn(helper.getLevel().getServer(), body);
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void only_mature_self_harvest_and_attributed_replant_count(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_farm", new BlockPos(5, 2, 5), false);
        farm(helper);
        SurvivalWorldObserver observer = new SurvivalWorldObserver();
        harvestAndPlant(helper, observer, body, new BlockPos(10, 2, 10), 7, body.getUUID());
        harvestAndPlant(helper, observer, body, new BlockPos(11, 2, 10), 3, body.getUUID());
        harvestAndPlant(helper, observer, body, new BlockPos(12, 2, 10), 7, UUID.randomUUID());
        helper.assertTrue(observer.observe(body).get("replanted_wheat").getAsInt() == 1,
                "unripe harvest and somebody else's planting must not count");
        harvestAndPlant(helper, observer, body, new BlockPos(10, 2, 10), 7, body.getUUID());
        harvestAndPlant(helper, observer, body, new BlockPos(13, 2, 10), 7, body.getOwnerUuid());
        helper.assertTrue(observer.observe(body).get("replanted_wheat").getAsInt() == 2,
                "repeat same cell counts once; build's owner-attributed cell counts");
        BlockPos secondHarvest = helper.absolutePos(new BlockPos(10, 2, 10));
        BlockState ripeAgain = Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
        helper.getLevel().setBlockAndUpdate(secondHarvest, Blocks.AIR.defaultBlockState());
        observer.broken(body, secondHarvest, ripeAgain);
        helper.assertTrue(observer.observe(body).get("replanted_wheat").getAsInt() == 2,
                "harvesting the next crop must retain the earlier completed replant cycle at this coordinate");
        BlockPos noReplant = helper.absolutePos(new BlockPos(10, 2, 11));
        BlockState mature = Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
        PlacedBlocks.of(helper.getLevel()).record(noReplant, new PlacedBlocks.Placer(body.getUUID(), "old crop"));
        helper.getLevel().setBlockAndUpdate(noReplant, Blocks.AIR.defaultBlockState());
        observer.broken(body, noReplant, mature);
        helper.getLevel().setBlockAndUpdate(noReplant, Blocks.WHEAT.defaultBlockState());
        helper.assertTrue(observer.observe(body).get("replanted_wheat").getAsInt() == 2,
                "a prior crop's stale placement record must not attribute a new villager seedling");
        CompanionFactory.despawn(helper.getLevel().getServer(), body);
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 600, batch = BATCH)
    public static void real_harvest_hook_and_native_seed_placement_join_the_evidence(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_native_farm", new BlockPos(4, 2, 12), false);
        BlockPos soil = helper.absolutePos(new BlockPos(6, 1, 12));
        BlockPos crop = soil.above();
        helper.getLevel().setBlockAndUpdate(soil,
                Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.getLevel().setBlockAndUpdate(crop,
                Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        body.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 2));
        PermissionStore rules = PermissionStore.of(helper.getLevel().getServer(), body.getOwnerUuid());
        rules.add(Verdict.Kind.ALLOW, Rule.parse("break(*)"));
        rules.add(Verdict.Kind.ALLOW, Rule.parse("place(*)"));
        WorldEvalObservers.start("sustainable-survival-v1", body);
        AtomicReference<ToolRun> plant = new AtomicReference<>();
        helper.startSequence().thenIdle(25).thenExecute(() -> {
            drop(helper, body, new BlockPos(4, 2, 12));
            helper.assertTrue(new BlockDigger(body).destroyNow(crop), "real mature wheat destruction must succeed");
            helper.assertTrue(helper.getLevel().getBlockState(crop).isAir(), "the harvested cell must really be empty");
            helper.assertTrue(WorldEvalObservers.observe(body).get("replanted_wheat").getAsInt() == 0,
                    "harvesting alone is not a completed production cycle");
            plant.set(command(body, "use block right " + xyz(soil) + " --item minecraft:wheat_seeds"));
        }).thenWaitUntil(() -> {
            helper.assertTrue(plant.get().done(), "native seed placement has not finished");
            helper.assertTrue(plant.get().succeeded(), "native planting failed: " + plant.get().outcome());
            helper.assertTrue(helper.getLevel().getBlockState(crop).is(Blocks.WHEAT), "the seed must become a real crop");
            helper.assertTrue(WorldEvalObservers.observe(body).get("replanted_wheat").getAsInt() == 1,
                    "BlockDigger hook and native BlockItem placer attribution must prove the cycle");
        }).thenExecute(() -> {
            WorldEvalObservers.stop(body.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 600, batch = BATCH)
    public static void shared_hands_harvest_and_native_replant_are_observed(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_shared_hands", new BlockPos(4, 2, 12), false);
        BlockPos soil = helper.absolutePos(new BlockPos(6, 1, 12));
        BlockPos crop = soil.above();
        helper.getLevel().setBlockAndUpdate(soil,
                Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.getLevel().setBlockAndUpdate(crop,
                Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        body.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 2));
        PermissionStore rules = PermissionStore.of(helper.getLevel().getServer(), body.getOwnerUuid());
        rules.add(Verdict.Kind.ALLOW, Rule.parse("break(*)"));
        rules.add(Verdict.Kind.ALLOW, Rule.parse("place(*)"));
        WorldEvalObservers.start("sustainable-survival-v1", body);
        AtomicReference<ToolRun> plant = new AtomicReference<>();
        helper.startSequence().thenIdle(25).thenExecute(() -> {
            drop(helper, body, new BlockPos(4, 2, 12));
            var strike = CompanionHands.of(body).dig(new BlockHitResult(Vec3.atCenterOf(crop), Direction.UP, crop, false));
            helper.assertTrue(strike instanceof com.dwinovo.numen.pathing.body.Effector.Strike.Broke,
                    "the shared navigation/interaction hands must report the real mature crop break: " + strike);
            helper.assertTrue(helper.getLevel().getBlockState(crop).isAir(), "shared hands must really remove the crop");
            helper.assertTrue(WorldEvalObservers.observe(body).get("replanted_wheat").getAsInt() == 0,
                    "shared-hands harvesting alone must not count as replanting");
            plant.set(command(body, "use block right " + xyz(soil) + " --item minecraft:wheat_seeds"));
        }).thenWaitUntil(() -> {
            helper.assertTrue(plant.get().done(), "native replant after shared-hands harvest has not finished");
            helper.assertTrue(plant.get().succeeded(), "native replant failed: " + plant.get().outcome());
            helper.assertTrue(helper.getLevel().getBlockState(crop).is(Blocks.WHEAT), "native seed must become a real crop");
            helper.assertTrue(WorldEvalObservers.observe(body).get("replanted_wheat").getAsInt() == 1,
                    "a navigation-port harvest and native replant must produce exactly one completed cell");
        }).thenExecute(() -> {
            WorldEvalObservers.stop(body.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void food_score_accepts_variety_and_excludes_harmful_effects(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_food", new BlockPos(5, 2, 5), false);
        body.getInventory().add(new ItemStack(Items.BREAD, 2));
        body.getInventory().add(new ItemStack(Items.APPLE));
        body.getInventory().add(new ItemStack(Items.COOKED_COD, 2));
        body.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 64));
        body.getInventory().add(new ItemStack(Items.POISONOUS_POTATO, 64));
        body.getInventory().add(new ItemStack(Items.PUFFERFISH, 64));
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW);
        stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS,
                new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 100))));
        body.getInventory().add(stew);
        helper.assertTrue(new SurvivalWorldObserver().observe(body).get("food_nutrition").getAsInt() == 24,
                "bread+apple+cod supply 24; harmful food and poisoned stew supply no safe reserve");
        CompanionFactory.despawn(helper.getLevel().getServer(), body);
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void wooden_gold_and_exhausted_tools_do_not_meet_stone_tier(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_eval_tools", new BlockPos(5, 2, 5), false);
        body.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        body.getInventory().add(new ItemStack(Items.GOLDEN_PICKAXE));
        ItemStack exhausted = new ItemStack(Items.STONE_HOE);
        exhausted.setDamageValue(exhausted.getMaxDamage());
        body.getInventory().add(exhausted);
        JsonObject first = new SurvivalWorldObserver().observe(body);
        helper.assertTrue(!first.get("usable_pickaxe").getAsBoolean(), "wood/gold cannot harvest iron ore");
        helper.assertTrue(!first.get("usable_hoe").getAsBoolean(), "exhausted hoe is not a usable farm tool");
        body.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        body.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        JsonObject second = new SurvivalWorldObserver().observe(body);
        helper.assertTrue(second.get("usable_pickaxe").getAsBoolean() && second.get("usable_hoe").getAsBoolean(),
                "iron pickaxe and wooden hoe qualify");
        CompanionFactory.despawn(helper.getLevel().getServer(), body);
        helper.succeed();
    }

    private static void room(GameTestHelper helper, boolean lit) {
        for (int x = 3; x <= 8; x++) {
            for (int z = 3; z <= 8; z++) {
                for (int y = 1; y <= 5; y++) {
                    boolean shell = x == 3 || x == 8 || z == 3 || z == 8 || y == 1 || y == 5;
                    helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        if (lit) helper.setBlock(new BlockPos(5, 5, 5), Blocks.GLOWSTONE);
        setDoor(helper, false);
    }

    private static void setDoor(GameTestHelper helper, boolean open) {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.OPEN, open);
        // 两半先一起落位,再通知邻居,不让原版把尚未配对的半扇门删掉。
        ServerLevel level = helper.getLevel();
        BlockPos lower = helper.absolutePos(new BlockPos(8, 2, 5));
        level.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    private static void farm(GameTestHelper helper) {
        helper.setBlock(new BlockPos(12, 1, 12), Blocks.WATER);
        for (int x = 10; x <= 13; x++) {
            for (int z = 10; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WHEAT);
            }
        }
    }

    private static void harvestAndPlant(GameTestHelper helper, SurvivalWorldObserver observer, NumenPlayer body,
                                        BlockPos relative, int age, UUID planter) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        BlockState crop = Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, age);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        observer.broken(body, pos, crop);
        level.setBlockAndUpdate(pos, Blocks.WHEAT.defaultBlockState());
        PlacedBlocks.of(level).record(pos, new PlacedBlocks.Placer(planter, "fixture planter"));
    }
}
