package com.dwinovo.numen.core.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** 自由生存的补给与现场状态:只记录原生消费和世界事实,不规定获取食物或避险路线。 */
public final class FreeSurvivalWorldObserver implements WorldEvalObservers.Observer {
    private static final int MAX_MEAL_EVIDENCE = 64;
    private final JsonArray meals = new JsonArray();
    private long startedTick = -1;
    private int consumedMeals;
    private int safeMeals;
    private int restoredFood;
    private JsonObject firstEffectiveMeal;

    @Override
    public void consumed(NumenPlayer body, ItemStack meal, int foodBefore, float saturationBefore) {
        consumedMeals++;
        int nutrition = SurvivalWorldObserver.safeNutrition(meal);
        int foodAfter = body.getFoodData().getFoodLevel();
        float saturationAfter = body.getFoodData().getSaturationLevel();
        boolean effective = nutrition > 0 && (foodAfter > foodBefore || saturationAfter > saturationBefore);
        if (effective) {
            safeMeals++;
            restoredFood += Math.max(0, foodAfter - foodBefore);
        }
        JsonObject entry = SurvivalWorldObserver.item(meal);
        entry.addProperty("game_tick", body.serverLevel().getServer().overworld().getGameTime());
        entry.addProperty("dimension", body.level().dimension().location().toString());
        entry.addProperty("food_before", foodBefore);
        entry.addProperty("food_after", foodAfter);
        entry.addProperty("saturation_before", saturationBefore);
        entry.addProperty("saturation_after", saturationAfter);
        entry.addProperty("safe_nutrition", nutrition);
        entry.addProperty("effective_safe_meal", effective);
        if (effective && firstEffectiveMeal == null) firstEffectiveMeal = entry.deepCopy();
        if (meals.size() < MAX_MEAL_EVIDENCE) meals.add(entry);
    }

    @Override
    public JsonObject observe(NumenPlayer body) {
        long tick = body.serverLevel().getServer().overworld().getGameTime();
        if (startedTick < 0) startedTick = tick;
        JsonObject facts = new JsonObject();
        JsonObject evidence = new JsonObject();
        JsonArray food = new JsonArray();
        int nutrition = 0;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            int value = SurvivalWorldObserver.safeNutrition(stack);
            if (value <= 0) continue;
            nutrition += value;
            JsonObject entry = SurvivalWorldObserver.item(stack);
            entry.addProperty("nutrition", value);
            food.add(entry);
        }
        JsonObject safety = safety(body);
        facts.addProperty("food_nutrition", nutrition);
        facts.addProperty("safe_meals", safeMeals);
        facts.addProperty("restored_food", restoredFood);
        facts.addProperty("elapsed_ticks", tick - startedTick);
        facts.addProperty("safe_state", safety.get("valid").getAsBoolean());
        evidence.addProperty("started_game_tick", startedTick);
        evidence.addProperty("scanned_game_tick", tick);
        evidence.addProperty("dimension", body.level().dimension().location().toString());
        evidence.add("food", food);
        evidence.add("meals", meals.deepCopy());
        evidence.add("first_effective_safe_meal", firstEffectiveMeal == null ? JsonNull.INSTANCE : firstEffectiveMeal.deepCopy());
        evidence.addProperty("consumed_meals", consumedMeals);
        evidence.addProperty("meal_evidence_truncated", consumedMeals > MAX_MEAL_EVIDENCE);
        evidence.add("safety", safety);
        facts.add("evidence", evidence);
        return facts;
    }

    static JsonObject safety(NumenPlayer body) {
        JsonObject out = new JsonObject();
        boolean fire = body.isOnFire();
        boolean lava = body.isInLava();
        boolean drowning = body.isEyeInFluid(FluidTags.WATER) && body.getAirSupply() <= 40;
        boolean frozen = body.isFullyFrozen();
        JsonArray harmful = new JsonArray();
        body.getActiveEffects().stream().filter(effect ->
                effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL).forEach(effect -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
                    entry.addProperty("duration_ticks", effect.getDuration());
                    entry.addProperty("amplifier", effect.getAmplifier());
                    harmful.add(entry);
                });
        JsonArray threats = new JsonArray();
        for (Mob mob : body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(16),
                mob -> mob.isAlive() && mob.getTarget() == body && mob.distanceToSqr(body) <= 256
                        && mob.hasLineOfSight(body))) {
            JsonObject entry = SurvivalWorldObserver.coordinates(mob.blockPosition());
            entry.addProperty("uuid", mob.getUUID().toString());
            entry.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
            threats.add(entry);
        }
        out.addProperty("valid", body.isAlive() && !fire && !lava && !drowning && !frozen
                && harmful.isEmpty() && threats.isEmpty());
        out.addProperty("on_fire", fire);
        out.addProperty("in_lava", lava);
        out.addProperty("drowning", drowning);
        out.addProperty("air_supply", body.getAirSupply());
        out.addProperty("fully_frozen", frozen);
        out.add("harmful_effects", harmful);
        out.add("visible_attackers_within_16", threats);
        return out;
    }
}
