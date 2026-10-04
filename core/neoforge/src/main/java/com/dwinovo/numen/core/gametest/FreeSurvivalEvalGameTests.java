package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 自由前期生存的评分夹具:原生进食和真实危险状态,不调用模型。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class FreeSurvivalEvalGameTests {
    private static final String BATCH = "numen_free_survival_eval";
    private static final String OBSERVER = "free-survival-v2";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, NOON);
    }

    /** 满状态空背包、直接改饥饿和拿走物品都不是一次进食补给。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void waiting_and_inventory_or_hunger_edits_do_not_create_meals(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_idle");
        body.getFoodData().setFoodLevel(20);
        JsonObject initial = WorldEvalObservers.observe(body);
        helper.assertTrue(initial.get("food_nutrition").getAsInt() == 0
                        && initial.get("safe_meals").getAsInt() == 0,
                "a full stomach and empty backpack must not prove food acquisition or eating: " + initial);
        body.getInventory().add(new ItemStack(Items.BREAD));
        helper.assertTrue(WorldEvalObservers.observe(body).get("food_nutrition").getAsInt() == 5,
                "the bread must be visible as a reserve before it is removed");
        body.getInventory().clearContent();
        body.getFoodData().setFoodLevel(10);
        body.getFoodData().setFoodLevel(20);

        helper.startSequence().thenIdle(20).thenExecute(() -> {
            JsonObject facts = WorldEvalObservers.observe(body);
            helper.assertTrue(facts.get("safe_meals").getAsInt() == 0
                            && facts.get("restored_food").getAsInt() == 0
                            && facts.get("food_nutrition").getAsInt() == 0,
                    "waiting, inventory removal and direct FoodData changes cannot manufacture a meal: " + facts);
            helper.assertTrue(facts.get("elapsed_ticks").getAsLong() >= 20,
                    "elapsed time must follow real world ticks: " + facts);
        }).thenSucceed();
    }

    /** 经真实 eat 工具吃掉一块面包,补饥饿并留下四块面包作为二十点储备。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void native_bread_meal_restores_food_and_leaves_a_reserve(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_bread");
        body.getFoodData().setFoodLevel(10);
        body.getFoodData().setSaturation(0);
        body.getInventory().add(new ItemStack(Items.BREAD, 5));
        ToolRun eat = call(body, "eat", args("item_id", "minecraft:bread"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done() && eat.succeeded(), "native bread eating has not succeeded: " + eat.outcome());
            JsonObject facts = WorldEvalObservers.observe(body);
            helper.assertTrue(body.getInventory().countItem(Items.BREAD) == 4
                            && facts.get("food_nutrition").getAsInt() == 20,
                    "the consumed bread must leave four real bread items supplying twenty nutrition: " + facts);
            helper.assertTrue(facts.get("safe_meals").getAsInt() == 1
                            && facts.get("restored_food").getAsInt() >= 5,
                    "native consumption must prove one effective meal: " + facts);
            helper.assertTrue(facts.getAsJsonObject("evidence").getAsJsonArray("meals").size() == 1
                            && facts.getAsJsonObject("evidence").getAsJsonArray("meals").toString().contains("minecraft:bread"),
                    "the meal evidence must identify the food actually consumed: " + facts);
        });
    }

    /** 满肚拒绝和嚼到一半停止都没有消费完成事件,等待完整咀嚼时长后也不补记。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void full_stomach_and_interrupted_eating_do_not_count(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_stopped_meal");
        body.getFoodData().setFoodLevel(20);
        body.getInventory().add(new ItemStack(Items.BREAD, 2));
        AtomicReference<ToolRun> eat = new AtomicReference<>(call(body, "eat", args("item_id", "minecraft:bread")));

        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(eat.get().done() && !eat.get().succeeded(),
                    "a full stomach must refuse ordinary bread: " + eat.get().outcome());
            helper.assertTrue(WorldEvalObservers.observe(body).get("safe_meals").getAsInt() == 0,
                    "a refused meal must not count");
        }).thenExecute(() -> {
            body.getFoodData().setFoodLevel(10);
            body.getFoodData().setSaturation(0);
            eat.set(call(body, "eat", args("item_id", "minecraft:bread")));
        }).thenIdle(10).thenExecute(() -> {
            helper.assertTrue(body.isUsingItem(), "the meal must be interrupted while native eating is in progress");
            CompanionTickDispatcher.cancelFor(body);
        }).thenWaitUntil(() -> helper.assertTrue(eat.get().done()
                        && eat.get().outcome().startsWith("the owner pressed Stop"),
                "the meal was not stopped: " + eat.get().outcome()))
                .thenIdle(40).thenWaitUntil(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    helper.assertTrue(body.getInventory().countItem(Items.BREAD) == 2
                                    && body.getFoodData().getFoodLevel() == 10
                                    && facts.get("safe_meals").getAsInt() == 0
                                    && facts.get("restored_food").getAsInt() == 0,
                            "the refused or stopped meal must keep its food and produce no score: " + facts);
                }).thenSucceed();
    }

    /** 蘑菇汤原生吃完会返碗;评分仍用消费前的食物组件,不能把碗当成吃下的东西。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void native_stew_meal_counts_before_it_turns_into_a_bowl(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_stew");
        body.getFoodData().setFoodLevel(10);
        body.getFoodData().setSaturation(0);
        body.getInventory().add(new ItemStack(Items.MUSHROOM_STEW));
        ToolRun eat = call(body, "eat", args("item_id", "minecraft:mushroom_stew"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done() && eat.succeeded(), "native stew eating has not succeeded: " + eat.outcome());
            JsonObject facts = WorldEvalObservers.observe(body);
            helper.assertTrue(body.getInventory().countItem(Items.MUSHROOM_STEW) == 0
                            && body.getInventory().countItem(Items.BOWL) == 1
                            && facts.get("food_nutrition").getAsInt() == 0,
                    "the stew must really be consumed and leave a non-food bowl: " + facts);
            helper.assertTrue(facts.get("safe_meals").getAsInt() == 1
                            && facts.get("restored_food").getAsInt() >= 6
                            && facts.getAsJsonObject("evidence").getAsJsonArray("meals").toString().contains("minecraft:mushroom_stew"),
                    "the original stew must appear as the effective safe meal: " + facts);
        });
    }

    /** 同一种炖菜的带毒组件必须从真实消费栈读取,吃完补了饥饿也不能算安全补给。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void harmful_stew_components_do_not_count_as_safe_food_or_meals(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_poisoned_stew");
        body.getFoodData().setFoodLevel(10);
        body.getFoodData().setSaturation(0);
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW);
        stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS,
                new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 100))));
        body.getInventory().add(stew);
        helper.assertTrue(WorldEvalObservers.observe(body).get("food_nutrition").getAsInt() == 0,
                "poisoned stew must not contribute to safe reserves");
        ToolRun eat = call(body, "eat", args("item_id", "minecraft:suspicious_stew"));

        helper.succeedWhen(() -> {
            helper.assertTrue(eat.done() && eat.succeeded(), "the poisoned stew must be eaten natively: " + eat.outcome());
            JsonObject facts = WorldEvalObservers.observe(body);
            helper.assertTrue(body.getInventory().countItem(Items.SUSPICIOUS_STEW) == 0
                            && body.getInventory().countItem(Items.BOWL) == 1
                            && body.hasEffect(MobEffects.POISON),
                    "the real stew consumption must apply its poison and return a bowl");
            helper.assertTrue(facts.get("safe_meals").getAsInt() == 0
                            && facts.get("restored_food").getAsInt() == 0
                            && !facts.get("safe_state").getAsBoolean(),
                    "harmful food cannot prove safe replenishment even when it restores hunger: " + facts);
        });
    }

    /** 开放地面不要求建筑;正在瞄准她且看得见她的近怪和有害效果才否定当前安全。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void open_ground_is_safe_until_a_visible_attacker_or_harmful_effect_exists(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_open_ground");
        helper.assertTrue(WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                "open ground without a building must qualify as a currently safe state");
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "zombie did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(9, 2, 5));
        zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        try {
            helper.assertTrue(WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                    "a nearby mob without this body as its target is not an active attacker");
            zombie.setTarget(body);
            helper.assertTrue(zombie.hasLineOfSight(body)
                            && !WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                    "a nearby visible mob targeting this body must invalidate safety");
            helper.setBlock(new BlockPos(7, 2, 5), Blocks.STONE);
            helper.setBlock(new BlockPos(7, 3, 5), Blocks.STONE);
            helper.assertTrue(!zombie.hasLineOfSight(body)
                            && WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                    "an occluded attacker must not satisfy the line-of-sight danger condition");
            zombie.discard();
            body.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200));
            helper.assertTrue(!WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                    "a harmful active effect must invalidate safety");
            body.removeEffect(MobEffects.HUNGER);
            helper.assertTrue(WorldEvalObservers.observe(body).get("safe_state").getAsBoolean(),
                    "removing the active danger must restore the current safety fact");
            helper.succeed();
        } finally {
            zombie.discard();
        }
    }

    /** 有界历史被不安全进食填满后,第六十五次真正有效的补给仍留下独立证据。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void first_effective_meal_remains_auditable_after_history_is_full(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_eval_meal_history");
        body.getInventory().setItem(0, new ItemStack(Items.ROTTEN_FLESH, 64));
        for (int i = 0; i < 64; i++) {
            ItemStack flesh = body.getInventory().getItem(0);
            body.getInventory().setItem(0, body.eat(body.serverLevel(), flesh, flesh.get(DataComponents.FOOD)));
        }
        JsonObject filled = WorldEvalObservers.observe(body);
        helper.assertTrue(body.getInventory().countItem(Items.ROTTEN_FLESH) == 0
                        && filled.getAsJsonObject("evidence").get("consumed_meals").getAsInt() == 64
                        && filled.get("safe_meals").getAsInt() == 0
                        && filled.getAsJsonObject("evidence").get("first_effective_safe_meal").isJsonNull(),
                "all sixty-four unsafe items must really be consumed without creating an effective safe meal: " + filled);

        // 下一餐前的显式夹具状态:去掉腐肉带来的饥饿效果,留下真实可补的饥饿与饱和度缺口。
        body.removeEffect(MobEffects.HUNGER);
        body.getFoodData().setFoodLevel(14);
        body.getFoodData().setSaturation(0);
        body.getInventory().setItem(0, new ItemStack(Items.BREAD));
        ItemStack bread = body.getInventory().getItem(0);
        body.getInventory().setItem(0, body.eat(body.serverLevel(), bread, bread.get(DataComponents.FOOD)));

        JsonObject facts = WorldEvalObservers.observe(body);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        helper.assertTrue(body.getInventory().countItem(Items.BREAD) == 0
                        && body.getFoodData().getFoodLevel() == 19
                        && facts.get("safe_meals").getAsInt() == 1
                        && facts.get("restored_food").getAsInt() == 5,
                "the sixty-fifth native consumption must use the bread and restore five food: " + facts);
        helper.assertTrue(evidence.getAsJsonArray("meals").size() == 64
                        && evidence.get("consumed_meals").getAsInt() == 65
                        && evidence.get("meal_evidence_truncated").getAsBoolean(),
                "the ordinary history must remain bounded while reporting the omitted consumption: " + facts);
        helper.assertTrue(evidence.get("first_effective_safe_meal").isJsonObject(),
                "the first effective meal needs independent evidence after the ordinary history is full: " + facts);
        JsonObject first = evidence.getAsJsonObject("first_effective_safe_meal");
        helper.assertTrue(first.get("id").getAsString().equals("minecraft:bread")
                        && first.get("effective_safe_meal").getAsBoolean(),
                "the retained first effective meal must be the real bread consumption: " + first);
        helper.succeed();
    }

    /** 成功和失败都释放观察器及身体,避免失败夹具污染之后的批次。 */
    private static NumenPlayer observedBody(GameTestHelper helper, String name) {
        NumenPlayer body = spawnAt(helper, name, new BlockPos(5, 2, 5), false);
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
