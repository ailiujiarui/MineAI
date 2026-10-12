package com.dwinovo.numen.gametest;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 第三阶段评分与事件证据校准:测试直接安排库存和换维,不证明模型会收集资源或建门。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class EndResourcesEvalGameTests {
    private static final String BATCH = "numen_end_resources_eval";
    private static final String OBSERVER = "end-resources-v1";
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Item[] IRON_ARMOR = {
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, NOON);
    }

    /** 合格远征起点的装备、食物和杂项既不是目标资源,也不是一次采样期间的资源获取。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void expedition_starter_has_zero_end_resources_and_no_invented_coverage(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_starter");
        equipRetainedSupplies(body);
        JsonObject facts = WorldEvalObservers.observe(body);
        helper.assertTrue(facts.get("eye_equivalents").getAsInt() == 0
                        && facts.get("pearl_units").getAsInt() == 0 && facts.get("blaze_units").getAsInt() == 0,
                "starter equipment and food must not manufacture any eye resource: " + facts);
        helper.assertTrue(facts.get("armor_ready").getAsBoolean() && facts.get("mining_tool_ready").getAsBoolean()
                        && facts.get("shield_ready").getAsBoolean() && facts.get("food_nutrition").getAsInt() == 80,
                "the zero-resource start must still be an actually equipped expedition fixture: " + facts);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        helper.assertTrue(evidence.get("events_total").getAsInt() == 0,
                "initial inventory must be a baseline, not an acquisition event: " + evidence);
        for (String kind : new String[]{"dimension_change", "hurt", "sampled_chunk_change", "inventory_change",
                "danger_entered", "danger_cleared"}) assertCoverage(helper, facts, kind, "not_covered", 0);
        assertCoverage(helper, facts, "risk_replanning", "not_measured", -1);
        assertCoverage(helper, facts, "resource_site_replanning", "not_measured", -1);
        helper.succeed();
    }

    /** 成品和原料混合时,成品只算一次,副手珍珠参与同一份制作量。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void mixed_finished_and_raw_resources_reach_sixteen_without_reusing_finished_eyes(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_mixed");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 4));
        body.getInventory().setItem(1, new ItemStack(Items.ENDER_PEARL, 6));
        body.getInventory().setItem(2, new ItemStack(Items.BLAZE_POWDER, 4));
        body.getInventory().setItem(3, new ItemStack(Items.BLAZE_ROD, 4));
        body.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ENDER_PEARL, 6));
        JsonObject facts = WorldEvalObservers.observe(body);
        assertCount(helper, facts, "eye_equivalents", 16);
        assertCount(helper, facts, "pearl_units", 16);
        assertCount(helper, facts, "blaze_units", 16);
        JsonObject resources = facts.getAsJsonObject("evidence").getAsJsonObject("resources");
        helper.assertTrue(resources.getAsJsonArray("slots").size() == 5
                        && resources.get("slots").toString().contains("\"slot\":40")
                        && resources.get("formula").getAsString().contains("min(ender_pearls"),
                "resource evidence must retain every contributing slot and the limiting-pair formula: " + resources);
        helper.succeed();
    }

    /** 一边再多也补不齐另一边的缺口;十五成品和一堆孤立原料都不算十六份。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void both_raw_dependencies_are_required_and_fifteen_is_not_sixteen(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_short");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 15));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 15);
        body.getInventory().clearContent();
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_PEARL, 16));
        body.getInventory().setItem(1, new ItemStack(Items.BLAZE_POWDER, 15));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 15);
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_PEARL, 15));
        body.getInventory().setItem(1, new ItemStack(Items.BLAZE_ROD, 64));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 15);
        body.getInventory().setItem(0, ItemStack.EMPTY);
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 0);
        helper.succeed();
    }

    /** 棒变粉、原料变眼只是形态变化,不能在历史和当前库存里再各算一遍。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void rod_to_powder_and_eye_crafting_preserve_current_equivalents(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_convert");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_PEARL, 16));
        body.getInventory().setItem(1, new ItemStack(Items.BLAZE_ROD, 8));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 16);
        body.getInventory().setItem(1, new ItemStack(Items.BLAZE_POWDER, 16));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 16);
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_PEARL, 10));
        body.getInventory().setItem(1, new ItemStack(Items.BLAZE_POWDER, 10));
        body.getInventory().setItem(2, new ItemStack(Items.ENDER_EYE, 6));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 16);
        body.getInventory().clearContent();
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 16));
        JsonObject facts = WorldEvalObservers.observe(body);
        assertCount(helper, facts, "eye_equivalents", 16);
        assertCount(helper, facts, "ender_pearls", 0);
        assertCount(helper, facts, "blaze_powder", 0);
        assertCount(helper, facts, "blaze_rods", 0);
        helper.succeed();
    }

    /** 曾经够数只留下增减证据,资源清空后必须立即归零。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void removed_resources_revoke_readiness_while_retaining_actual_inventory_delta_evidence(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_removed");
        JsonObject initial = WorldEvalObservers.observe(body);
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 16));
        assertCount(helper, WorldEvalObservers.observe(body), "eye_equivalents", 16);
        body.getInventory().clearContent();
        JsonObject facts = WorldEvalObservers.observe(body);
        assertCount(helper, facts, "eye_equivalents", 0);
        assertCoverage(helper, facts, "inventory_change", "observed", 2);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        helper.assertTrue(evidence.get("events").toString().contains("\"delta\":-16")
                        && evidence.getAsJsonObject("initial_sample").getAsJsonObject("resources")
                        .getAsJsonObject("counts").get("eye_equivalents").getAsInt() == 0
                        && initial.getAsJsonObject("evidence").get("events_total").getAsInt() == 0,
                "gain and loss must be reviewable while initial and returned snapshots remain immutable: " + evidence);
        helper.succeed();
    }

    /** 目标放到护甲槽、附近箱子或地上均不等于当前随身持有。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void armor_slots_external_chests_and_ground_drops_do_not_count(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_scope");
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ENDER_EYE, 16));
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ENDER_PEARL, 16));
        body.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.BLAZE_ROD, 8));
        BlockPos chestPos = helper.absolutePos(new BlockPos(8, 2, 8));
        helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.ENDER_EYE, 16));
        ItemEntity ground = new ItemEntity(helper.getLevel(), chestPos.getX(), chestPos.getY() + 1,
                chestPos.getZ(), new ItemStack(Items.ENDER_EYE, 16));
        ground.setNeverPickUp();
        helper.getLevel().addFreshEntity(ground);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
            private void cleanup() {
                ground.discard();
                chest.clearContent();
                helper.getLevel().setBlockAndUpdate(chestPos, Blocks.AIR.defaultBlockState());
            }
        });
        JsonObject facts = WorldEvalObservers.observe(body);
        assertCount(helper, facts, "eye_equivalents", 0);
        assertCount(helper, facts, "ender_pearls", 0);
        assertCount(helper, facts, "blaze_rods", 0);
        helper.assertTrue(facts.getAsJsonObject("evidence").getAsJsonObject("resources")
                        .getAsJsonArray("slots").isEmpty(), "excluded locations cannot appear as contributing slots");
        helper.succeed();
    }

    /** 第三阶段每件各自至少半耐久,恢复某件也不能用另一个残损装备凑数。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void every_retained_armor_piece_pickaxe_and_shield_requires_fifty_percent(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_durability");
        equipIronArmor(body, true);
        body.getInventory().setItem(0, atBoundary(Items.DIAMOND_PICKAXE));
        body.setItemSlot(EquipmentSlot.OFFHAND, atBoundary(Items.SHIELD));
        for (EquipmentSlot slot : ARMOR) {
            assertFact(helper, body, "armor_ready", true);
            ItemStack piece = body.getItemBySlot(slot);
            piece.setDamageValue(piece.getDamageValue() + 1);
            assertFact(helper, body, "armor_ready", false);
            piece.setDamageValue(piece.getDamageValue() - 1);
        }
        assertFact(helper, body, "armor_ready", true);
        assertFact(helper, body, "mining_tool_ready", true);
        body.getInventory().getItem(0).setDamageValue(body.getInventory().getItem(0).getDamageValue() + 1);
        assertFact(helper, body, "mining_tool_ready", false);
        assertFact(helper, body, "shield_ready", true);
        ItemStack shield = body.getOffhandItem();
        shield.setDamageValue(shield.getDamageValue() + 1);
        assertFact(helper, body, "shield_ready", false);
        helper.succeed();
    }

    /** 危险出现和解除只记录发生过的状态变化;足够资源不会掩盖当前危险或食物缺口。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void safety_food_and_sampled_risk_evidence_remain_independent_of_target_resources(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_risk");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 16));
        body.getInventory().setItem(1, new ItemStack(Items.BREAD, 16));
        body.getInventory().setItem(2, new ItemStack(Items.ROTTEN_FLESH, 64));
        JsonObject before = WorldEvalObservers.observe(body);
        assertCount(helper, before, "food_nutrition", 80);
        assertCoverage(helper, before, "danger_entered", "not_covered", 0);
        body.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200));
        JsonObject unsafe = WorldEvalObservers.observe(body);
        helper.assertTrue(!unsafe.get("safe_state").getAsBoolean(), "sixteen eye equivalents cannot negate active danger");
        assertCount(helper, unsafe, "eye_equivalents", 16);
        assertCoverage(helper, unsafe, "danger_entered", "observed", 1);
        body.removeEffect(MobEffects.HUNGER);
        body.getInventory().setItem(1, ItemStack.EMPTY);
        JsonObject after = WorldEvalObservers.observe(body);
        helper.assertTrue(after.get("safe_state").getAsBoolean(), "cleared danger must restore current safety");
        assertCount(helper, after, "food_nutrition", 0);
        assertCoverage(helper, after, "danger_cleared", "observed", 1);
        assertCoverage(helper, after, "risk_replanning", "not_measured", -1);
        helper.succeed();
    }

    /** 测试用原版传送明确校准加载器回执;未跑采样也必须保留原生换维和实际受伤事件。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void native_dimension_and_accepted_damage_events_are_recorded_without_inventing_a_route(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_native");
        JsonObject before = WorldEvalObservers.observe(body);
        assertCoverage(helper, before, "dimension_change", "not_covered", 0);
        assertCoverage(helper, before, "hurt", "not_covered", 0);
        // 原版新生玩家有六十刻出生保护;和生存伤害用例一样等七十刻,再走真实伤害结算。
        steps(helper)
                .thenExecuteAfter(70, () -> {
                    helper.assertTrue(body.hurt(helper.getLevel().damageSources().generic(), 2), "native generic damage must be accepted");
                    body.setInvulnerable(true);
                    helper.assertTrue(!body.hurt(helper.getLevel().damageSources().generic(), 2), "immune damage must be rejected");
                    body.setInvulnerable(false);
                    ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
                    helper.assertTrue(nether != null, "the fixture server must have a nether dimension");
                    body.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 70, 0.5), Vec3.ZERO,
                            0, 0, DimensionTransition.DO_NOTHING));
                    JsonObject after = WorldEvalObservers.observe(body);
                    assertCoverage(helper, after, "dimension_change", "observed", 1);
                    assertCoverage(helper, after, "hurt", "observed", 1);
                    JsonObject coverage = after.getAsJsonObject("evidence").getAsJsonObject("coverage");
                    JsonObject dimension = coverage.getAsJsonObject("dimension_change").getAsJsonObject("first_event");
                    helper.assertTrue(dimension.getAsJsonObject("details").get("to_dimension").getAsString().equals("minecraft:the_nether")
                                    && !dimension.getAsJsonObject("details").has("from_dimension")
                                    && !dimension.getAsJsonObject("details").has("portal_built"),
                            "native evidence must identify the actual destination without guessing an origin or portal route: " + dimension);
                    JsonObject hurt = coverage.getAsJsonObject("hurt").getAsJsonObject("first_event").getAsJsonObject("details");
                    helper.assertTrue(hurt.get("reported_amount_before_mitigation").getAsFloat() == 2
                                    && hurt.get("damage_type_message_id").getAsString().equals("generic")
                                    && hurt.get("health_after").getAsFloat() < body.getMaxHealth(),
                            "native hurt evidence must preserve source, reported amount and actual resulting health: " + hurt);
                })
                .thenSucceed();
    }

    /** 采样跨区与有界事件保留真实总数和首次证据;日志截断不会膨胀资源。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void sampled_exploration_and_truncated_inventory_history_keep_first_evidence(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_history");
        WorldEvalObservers.observe(body);
        body.moveTo(body.getX() + 32, body.getY(), body.getZ());
        assertCoverage(helper, WorldEvalObservers.observe(body), "sampled_chunk_change", "observed", 1);
        for (int i = 0; i < 140; i++) {
            body.getInventory().setItem(0, i % 2 == 0 ? new ItemStack(Items.ENDER_EYE) : ItemStack.EMPTY);
            WorldEvalObservers.observe(body);
        }
        JsonObject facts = WorldEvalObservers.observe(body);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        assertCount(helper, facts, "eye_equivalents", 0);
        assertCoverage(helper, facts, "inventory_change", "observed", 140);
        assertCoverage(helper, facts, "dimension_change", "not_covered", 0);
        helper.assertTrue(evidence.getAsJsonArray("events").size() == 128
                        && evidence.get("events_total").getAsInt() == 141
                        && evidence.get("events_truncated").getAsBoolean()
                        && evidence.getAsJsonArray("visited_chunks").size() == 2,
                "bounded evidence must retain truthful totals, truncation and sampled visited chunks: " + evidence);
        helper.succeed();
    }

    /** 两次客户端采样间缺盾再恢复仍打断保持;只有真实的服务端连续刻能重新攒到二百。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void server_tick_hold_detects_a_missing_shield_between_client_samples(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_end_hold");
        equipRetainedSupplies(body);
        body.getInventory().add(new ItemStack(Items.ENDER_EYE, 16));
        helper.assertTrue(WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt() == 0,
                "initial scanning cannot contribute a server tick");
        final int[] before = {0};
        steps(helper)
                .thenIdle(5)
                .thenExecute(() -> {
                    before[0] = WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt();
                    helper.assertTrue(before[0] >= 3 && before[0] < 200,
                            "valid native ticks must accumulate but cannot yet prove two hundred: " + before[0]);
                    helper.assertTrue(WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt() == before[0],
                            "repeated client observations in one tick must not add hold time");
                    WorldEvalObservers.tick(body);
                    int once = WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt();
                    WorldEvalObservers.tick(body);
                    helper.assertTrue(WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt() == once,
                            "duplicate dispatch in the same game tick must not add hold time");
                    body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                })
                .thenIdle(2)
                .thenExecute(() -> body.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD)))
                .thenIdle(2)
                .thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    int held = facts.get("loadout_held_ticks").getAsInt();
                    helper.assertTrue(facts.get("shield_ready").getAsBoolean() && held > 0 && held <= 3 && held < before[0],
                            "the restored shield must restart native hold despite both client samples being equipped: " + facts);
                })
                .thenIdle(200)
                .thenExecute(() -> helper.assertTrue(WorldEvalObservers.observe(body).get("loadout_held_ticks").getAsInt() >= 200,
                        "two hundred actual valid server ticks after restoration must establish continuous hold"))
                .thenSucceed();
    }

    private static void equipRetainedSupplies(NumenPlayer body) {
        equipIronArmor(body, false);
        body.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        body.getInventory().add(new ItemStack(Items.IRON_SWORD));
        body.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        body.getInventory().add(new ItemStack(Items.TORCH, 32));
        body.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        body.getInventory().add(new ItemStack(Items.FURNACE));
        body.getInventory().add(new ItemStack(Items.BREAD, 16));
        body.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        body.setHealth(body.getMaxHealth());
        body.getFoodData().setFoodLevel(20);
    }

    private static void equipIronArmor(NumenPlayer body, boolean boundary) {
        for (int i = 0; i < ARMOR.length; i++) body.setItemSlot(ARMOR[i],
                boundary ? atBoundary(IRON_ARMOR[i]) : new ItemStack(IRON_ARMOR[i]));
    }

    /** 奇数总耐久按向上取整的半耐久接受,再多损一格就低于一半。 */
    private static ItemStack atBoundary(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(stack.getMaxDamage() / 2);
        return stack;
    }

    private static void assertFact(GameTestHelper helper, NumenPlayer body, String key, boolean expected) {
        JsonObject facts = WorldEvalObservers.observe(body);
        helper.assertTrue(facts.get(key).getAsBoolean() == expected, key + " must equal " + expected + ": " + facts);
    }

    private static void assertCount(GameTestHelper helper, JsonObject facts, String key, int expected) {
        helper.assertTrue(facts.get(key).getAsInt() == expected, key + " must equal " + expected + ": " + facts);
    }

    private static void assertCoverage(GameTestHelper helper, JsonObject facts, String key, String status, int count) {
        JsonObject entry = facts.getAsJsonObject("evidence").getAsJsonObject("coverage").getAsJsonObject(key);
        helper.assertTrue(entry.get("status").getAsString().equals(status), key + " must be " + status + ": " + entry);
        if (count >= 0) helper.assertTrue(entry.get("sampled_events").getAsInt() == count
                        && (count == 0 ? entry.get("first_event").isJsonNull() : entry.get("first_event").isJsonObject()),
                key + " must preserve count and truthful first-event evidence: " + entry);
    }

    /** 成功和失败均释放观察器及身体,包括用例中跨到下界的同一具身体。 */
    private static NumenPlayer observedBody(GameTestHelper helper, String name) {
        NumenPlayer body = spawnAt(helper, name, new BlockPos(5, 2, 5), false);
        body.getInventory().clearContent();
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
            private void cleanup() {
                WorldEvalObservers.stop(body.getUUID());
                CompanionFactory.despawn(helper.getLevel().getServer(), body);
            }
        });
        WorldEvalObservers.start(OBSERVER, body);
        return body;
    }
}
