package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 远征准备评分校准:真实穿戴、耐久边界和物资余量,不调用模型。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ExpeditionEvalGameTests {
    private static final String BATCH = "numen_expedition_eval";
    private static final String OBSERVER = "expedition-v1";
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Item[] IRON_ARMOR = {
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, NOON);
    }

    /** 背包里有全套铁甲不等于穿好了;缺件或用金甲补位也不能通过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void armor_must_be_worn_complete_and_at_least_iron(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_expedition_armor");
        for (Item piece : IRON_ARMOR) body.getInventory().add(new ItemStack(piece));
        assertFact(helper, body, "armor_ready", false, "carrying four iron pieces is not wearing them");

        body.getInventory().clearContent();
        for (int i = 0; i < ARMOR.length; i++) body.setItemSlot(ARMOR[i], new ItemStack(IRON_ARMOR[i]));
        assertFact(helper, body, "armor_ready", true, "all four full-durability iron pieces must qualify");
        body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        assertFact(helper, body, "armor_ready", false, "three pieces must not qualify as a full set");
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
        assertFact(helper, body, "armor_ready", false, "gold cannot replace the missing iron-tier chestplate");
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        assertFact(helper, body, "armor_ready", true, "restoring the qualifying chestplate must restore readiness");
        helper.succeed();
    }

    /** 铁、钻石、下界合金可混穿;每件各自检查八成耐久,不能靠其余三件抵消。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void mixed_armor_passes_at_the_boundary_but_one_worn_piece_below_it_fails(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_expedition_mixed");
        Item[] pieces = {Items.IRON_HELMET, Items.DIAMOND_CHESTPLATE,
                Items.NETHERITE_LEGGINGS, Items.DIAMOND_BOOTS};
        for (int i = 0; i < ARMOR.length; i++) body.setItemSlot(ARMOR[i], atBoundary(pieces[i]));
        assertFact(helper, body, "armor_ready", true,
                "mixed accepted materials must qualify at their lowest integer durability at or above eighty percent");
        for (EquipmentSlot slot : ARMOR) {
            ItemStack piece = body.getItemBySlot(slot);
            int acceptedDamage = piece.getDamageValue();
            piece.setDamageValue(acceptedDamage + 1);
            assertFact(helper, body, "armor_ready", false, "one below-boundary " + slot + " must invalidate the set");
            piece.setDamageValue(acceptedDamage);
            assertFact(helper, body, "armor_ready", true, "restoring " + slot + " must restore readiness");
        }
        JsonObject evidence = WorldEvalObservers.observe(body).getAsJsonObject("evidence");
        helper.assertTrue(evidence.has("armor") && evidence.get("armor").toString().contains("minecraft:diamond_chestplate")
                        && evidence.get("armor").toString().contains("remaining_durability"),
                "armor evidence must identify the worn piece and its remaining durability: " + evidence);
        helper.succeed();
    }

    /** 石镐不够档次,残损铁镐不够余量;无论格子顺序如何,合格镐都应被发现。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void mining_tool_requires_tier_and_reserve_without_a_bad_stack_masking_a_good_one(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_expedition_pick");
        body.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
        body.getInventory().setItem(1, new ItemStack(Items.GOLDEN_PICKAXE));
        assertFact(helper, body, "mining_tool_ready", false, "stone and gold cannot replace an iron-tier pickaxe");

        ItemStack worn = atBoundary(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getDamageValue() + 1);
        body.getInventory().setItem(0, worn);
        assertFact(helper, body, "mining_tool_ready", false, "an iron pickaxe below eighty percent must fail");
        ItemStack ready = atBoundary(Items.IRON_PICKAXE);
        body.getInventory().setItem(1, ready);
        assertFact(helper, body, "mining_tool_ready", true, "a worn pickaxe before a ready one must not hide it");
        body.getInventory().setItem(0, ready);
        body.getInventory().setItem(1, worn);
        assertFact(helper, body, "mining_tool_ready", true, "a worn pickaxe after a ready one must not erase it");

        body.getInventory().clearContent();
        body.setItemSlot(EquipmentSlot.OFFHAND, atBoundary(Items.NETHERITE_PICKAXE));
        assertFact(helper, body, "mining_tool_ready", true, "a qualifying higher-tier pickaxe in the offhand must count");
        body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        assertFact(helper, body, "mining_tool_ready", false, "removing the last ready pickaxe must immediately revoke readiness");
        helper.succeed();
    }

    /** 盾牌可以携带或放副手,但一件残损盾不能证明已经准备好出发。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void shield_inventory_and_offhand_use_the_same_durability_boundary(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_expedition_shield");
        ItemStack shield = atBoundary(Items.SHIELD);
        body.getInventory().setItem(0, shield);
        assertFact(helper, body, "shield_ready", true, "a backpack shield at the boundary must qualify");
        body.getInventory().setItem(0, ItemStack.EMPTY);
        assertFact(helper, body, "shield_ready", false, "an absent shield must not retain an earlier ready fact");
        body.setItemSlot(EquipmentSlot.OFFHAND, shield);
        assertFact(helper, body, "shield_ready", true, "the same real shield moved to the offhand must qualify");
        shield.setDamageValue(shield.getDamageValue() + 1);
        assertFact(helper, body, "shield_ready", false, "one durability below the threshold must invalidate an offhand shield");
        JsonObject evidence = WorldEvalObservers.observe(body).getAsJsonObject("evidence");
        helper.assertTrue(evidence.has("inventory") && evidence.get("inventory").toString().contains("minecraft:shield")
                        && evidence.get("inventory").toString().contains("slot")
                        && evidence.get("inventory").toString().contains("remaining_durability"),
                "inventory evidence must retain the actual shield slot and durability even when it fails: " + evidence);
        helper.succeed();
    }

    /** 混合安全食物按营养加总;腐肉、带毒组件和当前有害状态不能被储备掩盖。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void food_reserves_exclude_harmful_components_and_current_danger_is_independent(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_expedition_food");
        body.getInventory().add(new ItemStack(Items.BREAD, 2));
        body.getInventory().add(new ItemStack(Items.APPLE));
        body.getInventory().add(new ItemStack(Items.COOKED_COD, 2));
        body.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 64));
        body.getInventory().add(new ItemStack(Items.PUFFERFISH, 64));
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW);
        stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS,
                new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 100))));
        body.getInventory().add(stew);
        JsonObject facts = WorldEvalObservers.observe(body);
        helper.assertTrue(facts.get("food_nutrition").getAsInt() == 24,
                "two bread, one apple and two cod supply twenty-four safe nutrition; harmful items supply none: " + facts);
        assertFact(helper, body, "safe_state", true, "open ground without current danger must be safe");
        body.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200));
        assertFact(helper, body, "safe_state", false, "enough food does not negate an active harmful effect");
        body.removeEffect(MobEffects.HUNGER);
        assertFact(helper, body, "safe_state", true, "removing the harmful effect must restore current safety");
        body.getInventory().clearContent();
        helper.assertTrue(WorldEvalObservers.observe(body).get("food_nutrition").getAsInt() == 0,
                "removed food must stop contributing to the reserve immediately");
        helper.succeed();
    }

    /** 整数耐久没有恰好八成时,取仍不少于八成的最小剩余值。 */
    private static ItemStack atBoundary(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(stack.getMaxDamage() / 5);
        return stack;
    }

    private static void assertFact(GameTestHelper helper, NumenPlayer body, String fact,
                                   boolean expected, String message) {
        JsonObject facts = WorldEvalObservers.observe(body);
        helper.assertTrue(facts.get(fact).getAsBoolean() == expected, message + ": " + facts);
    }

    /** 成功和失败都释放观察器及身体,避免失败夹具污染后续批次。 */
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
