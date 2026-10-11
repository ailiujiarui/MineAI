package com.dwinovo.numen.core.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;

/** 同一原生战斗的扣血、致死、归属、统计及动画结束只读链;不替身体选打法或行动。 */
public final class DragonCombatWorldObserver implements WorldEvalObservers.Observer {
    private static final int MAX_EVENTS = 256;
    private static final String[] KINDS = {"dragon_damage", "dragon_healed", "dragon_phase_changed", "crystals_changed",
            "dragon_lethal", "kill_credit", "kill_statistic", "dragon_fight_end", "hurt", "food_consumed",
            "inventory_change", "equipment_changed", "retreat", "dimension_change", "observation_gap", "death"};
    private final EndResourcesWorldObserver resources = new EndResourcesWorldObserver();
    private final JsonArray events = new JsonArray();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, JsonObject> first = new LinkedHashMap<>();
    private final boolean calibrationFixture;
    private ServerLevel battleLevel;
    private EndDragonFight fight;
    private NumenPlayer subject;
    private UUID subjectUuid;
    private UUID targetUuid;
    private EnderDragon target;
    private String fightId;
    private JsonObject initialBattle;
    private JsonObject initialSample;
    private JsonObject currentSample;
    private JsonObject firstDamage;
    private JsonObject firstCompanionDamage;
    private JsonObject lethal;
    private JsonObject killCredit;
    private JsonObject killStatistic;
    private JsonObject fightEnd;
    private DamageSource lethalSource;
    private JsonObject previousBattle;
    private JsonObject previousResources;
    private Map<String, Integer> previousInventory;
    private long lastTick = -1;
    private long lastDamageEventId = -1;
    private float totalActualDamage;
    private float companionActualDamage;
    private float minimumHealth;
    private float lastObservedHealth;
    private int gaps;
    private int eventsTotal;
    private int postKillAliveTicks;
    private boolean dead;
    private boolean targetGap;
    private boolean identityGap;
    private boolean ambiguous;
    private boolean ready;

    public DragonCombatWorldObserver() { calibrationFixture = false; }

    /** 公开人工 GameTest 夹具仅校准原生回执,永远不满足正式场景的起点检查。 */
    public DragonCombatWorldObserver(ServerLevel level, EndDragonFight nativeFixtureFight) {
        calibrationFixture = true;
        battleLevel = level;
        fight = nativeFixtureFight;
    }

    @Override public void started(NumenPlayer body) {
        subject = body;
        subjectUuid = body.getUUID();
        if (!calibrationFixture) {
            battleLevel = body.serverLevel().getServer().getLevel(Level.END);
            fight = battleLevel == null ? null : battleLevel.getDragonFight();
        }
        targetUuid = fight == null ? null : fight.getDragonUUID();
        Entity entity = targetUuid == null || battleLevel == null ? null : battleLevel.getEntity(targetUuid);
        target = entity instanceof EnderDragon dragon ? dragon : null;
        BlockPos origin = target == null ? BlockPos.ZERO : target.getFightOrigin();
        fightId = battleLevel == null || targetUuid == null ? null
                : battleLevel.dimension().location() + ":" + origin.getX() + ":" + origin.getY() + ":" + origin.getZ() + ":" + targetUuid;
        minimumHealth = target == null ? 0 : target.getHealth();
        lastObservedHealth = minimumHealth;
        initialBattle = battle();
        initialBattle.addProperty("evidence_source", "server_native_dragon_fight_initial_state");
        ready = !calibrationFixture && body.level().dimension() == Level.END && bound()
                && !fight.saveData().needsStateScanning() && !fight.saveData().dragonKilled()
                && !fight.saveData().previouslyKilled() && !fight.saveData().isRespawning()
                && target.getHealth() == 200 && target.getMaxHealth() == 200 && !target.isNoAi()
                && !target.isInvulnerable() && target.dragonDeathTime == 0
                && target.getPhaseManager().getCurrentPhase().getPhase() != net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.DYING
                && stats(body) == 0 && initialCrystalsComplete(initialBattle.getAsJsonArray("crystals"))
                && fight.getCrystalsAlive() == 10;
        sampleBody(body);
        previousBattle = initialBattle.deepCopy();
        lastTick = now();
    }

    @Override public void tick(NumenPlayer body) {
        subject = body;
        long tick = now();
        if (tick == lastTick) return;
        if (lastTick >= 0 && tick != lastTick + 1) unavailable(tick);
        if (!body.isAlive()) latchDeath(body, "server_tick_dead_body");
        if (fight != null && (!calibrationFixture && battleLevel.getDragonFight() != fight
                || !java.util.Objects.equals(fight.getDragonUUID(), targetUuid))) {
            if (!identityGap) { identityGap = true; unavailable(tick); }
        }
        Entity entity = targetUuid == null || battleLevel == null ? null : battleLevel.getEntity(targetUuid);
        if (entity != target && fightEnd == null && !targetGap) { targetGap = true; unavailable(tick); }
        JsonObject current = battle();
        if (!current.get("target_health").isJsonNull()) minimumHealth = Math.min(minimumHealth, current.get("target_health").getAsFloat());
        if (previousBattle != null && current.get("target_present").getAsBoolean()) {
            float health = current.get("target_health").getAsFloat();
            float before = lastObservedHealth;
            // 扣血只在 reallyHurt 边界累计;回血是服务端采样事实,不重复算作伤害。
            if (health > before && !current.get("phase").getAsString().startsWith("Dying")) {
                JsonObject event = identity("server_dragon_health_increase_sample");
                event.addProperty("health_before", before); event.addProperty("health_after", health);
                record("dragon_healed", event);
            }
            lastObservedHealth = health;
            if (!current.get("phase").equals(previousBattle.get("phase"))) record("dragon_phase_changed", current);
        }
        if (previousBattle != null && !current.get("crystals_alive").equals(previousBattle.get("crystals_alive"))) {
            JsonObject event = current.deepCopy();
            event.addProperty("evidence_source", "server_crystal_count_sample");
            event.addProperty("attributed_to_companion", false);
            record("crystals_changed", event);
        }
        previousBattle = current;
        sampleBody(body);
        boolean aliveAfterKill = terminalChain() && fightEnd != null && tick > fightEnd.get("game_tick").getAsLong()
                && body.isAlive() && !dead && gaps == 0 && !ambiguous;
        postKillAliveTicks = aliveAfterKill ? postKillAliveTicks + 1 : 0;
        lastTick = tick;
    }

    @Override public void unavailable(long tick) {
        gaps++;
        postKillAliveTicks = 0;
        JsonObject event = identity("server_observation_gap");
        event.addProperty("game_tick", tick);
        record("observation_gap", event);
    }

    @Override public void entityDamaged(Entity victim, DamageSource source, float before, float after, boolean accepted,
                                         String origin, Entity originatingActor, long eventId) {
        if (!sameTarget(victim) || eventId <= lastDamageEventId) return;
        lastDamageEventId = eventId;
        if (!accepted || !Float.isFinite(before) || !Float.isFinite(after) || before <= after) return;
        JsonObject event = damageFacts(source, "native_ender_dragon_really_hurt");
        event.addProperty("event_id", eventId);
        event.addProperty("health_before", before);
        event.addProperty("health_after", after);
        event.addProperty("actual_health_loss", before - after);
        event.addProperty("attribution_origin", origin);
        uuid(event, "provenance_actor_uuid", originatingActor == null ? null : originatingActor.getUUID());
        uuid(event, "actor_uuid", source.getEntity() == null ? null : source.getEntity().getUUID());
        boolean reliable = !origin.equals("crystal_nearest_player_fallback") && !source.is(DamageTypes.GENERIC_KILL);
        event.addProperty("attribution_reliable", reliable);
        totalActualDamage += before - after;
        minimumHealth = Math.min(minimumHealth, after);
        lastObservedHealth = after;
        if (firstDamage == null) firstDamage = event.deepCopy();
        if (reliable && originatingActor != null && originatingActor.getUUID().equals(subjectUuid)) {
            companionActualDamage += before - after;
            if (firstCompanionDamage == null) firstCompanionDamage = event.deepCopy();
        }
        if (lethal != null && source == lethalSource && after == 0 && before > 0) {
            lethal.addProperty("damage_event_id", eventId);
            lethal.addProperty("health_before", before);
            lethal.addProperty("health_after", after);
            lethal.addProperty("actual_health_loss", before - after);
            lethal.addProperty("attribution_origin", origin);
            uuid(lethal, "provenance_actor_uuid", originatingActor == null ? null : originatingActor.getUUID());
            lethal.add("fatal_damage", event.deepCopy());
            if (!reliable || originatingActor == null) ambiguous = true;
        }
        record("dragon_damage", event);
    }

    @Override public void entityDied(LivingEntity victim, DamageSource source, LivingEntity credit) {
        if (!sameTarget(victim) || lethal != null) return;
        lethalSource = source;
        lethal = damageFacts(source, "native_living_entity_die");
        lethal.addProperty("health", victim.getHealth());
        uuid(lethal, "credited_actor_uuid", credit == null ? null : credit.getUUID());
        lethal.addProperty("attribution_origin", "unverified_damage_origin");
        record("dragon_lethal", lethal);
    }

    @Override public void killCredit(ServerPlayer actor, Entity victim, DamageSource source) {
        if (!sameTarget(victim) || killCredit != null) return;
        killCredit = damageFacts(source, "native_server_player_award_kill_score");
        uuid(killCredit, "actor_uuid", actor.getUUID());
        record("kill_credit", killCredit);
    }

    @Override public void killStatistic(Player actor, LivingEntity victim, int before, int after) {
        if (!sameTarget(victim) || killStatistic != null) return;
        killStatistic = identity("native_player_killed_entity_statistic");
        uuid(killStatistic, "actor_uuid", actor.getUUID());
        killStatistic.addProperty("before", before);
        killStatistic.addProperty("after", after);
        record("kill_statistic", killStatistic);
    }

    @Override public void dragonFightEnded(EndDragonFight ended, EnderDragon dragon) {
        if (fight != ended || !sameTarget(dragon) || fightEnd != null) return;
        fightEnd = identity("native_end_dragon_fight_set_dragon_killed");
        fightEnd.addProperty("dragon_killed", ended.saveData().dragonKilled());
        fightEnd.addProperty("previously_killed", ended.saveData().previouslyKilled());
        fightEnd.addProperty("death_animation_ticks", dragon.dragonDeathTime);
        record("dragon_fight_end", fightEnd);
    }

    @Override public void died(NumenPlayer body) { latchDeath(body, "native_companion_death_before_recovery"); }
    private void latchDeath(NumenPlayer body, String source) {
        if (dead) return;
        subject = body; dead = true;
        postKillAliveTicks = 0;
        JsonObject event = identity(source);
        event.addProperty("health_at_death", body.getHealth());
        record("death", event);
    }

    @Override public void hurt(NumenPlayer body, DamageSource source, float amount) {
        JsonObject event = damageFacts(source, "native_companion_accepted_damage_event");
        event.addProperty("reported_amount_before_mitigation", amount);
        event.addProperty("health_after", body.getHealth());
        record("hurt", event);
    }
    @Override public void consumed(NumenPlayer body, ItemStack meal, int foodBefore, float saturationBefore) {
        JsonObject event = identity("native_food_consumed");
        event.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(meal.getItem()).toString());
        event.addProperty("food_before", foodBefore); event.addProperty("food_after", body.getFoodData().getFoodLevel());
        record("food_consumed", event);
    }
    @Override public void dimensionChanged(NumenPlayer body) {
        subject = body;
        JsonObject event = identity("native_dimension_change_hook");
        event.addProperty("to_dimension", body.level().dimension().location().toString());
        record("dimension_change", event);
    }

    @Override public JsonObject observe(NumenPlayer body) {
        subject = body;
        sampleBody(body);
        JsonObject facts = new JsonObject();
        facts.addProperty("subject_uuid", subjectUuid.toString());
        facts.addProperty("dragon_combat_ready", ready);
        facts.addProperty("observation_complete", gaps == 0);
        facts.addProperty("dragon_damaged", firstCompanionDamage != null);
        facts.addProperty("dragon_death_started", lethal != null && lethal.has("fatal_damage"));
        facts.addProperty("native_dragon_kill", completeChain() && ready && gaps == 0 && !dead && !ambiguous);
        facts.addProperty("dragon_post_kill_alive_ticks", postKillAliveTicks);
        facts.addProperty("total_actual_damage", totalActualDamage);
        facts.addProperty("companion_actual_damage", companionActualDamage);
        facts.addProperty("dragon_minimum_health", minimumHealth);
        JsonObject current = battle();
        facts.add("dragon_current_health", current.get("target_health").deepCopy());
        facts.add("crystals_alive", current.get("crystals_alive").deepCopy());
        JsonObject chain = identity("server_dragon_combat_observation");
        chain.add("initial_battle", initialBattle.deepCopy());
        chain.add("current_battle", current);
        retained(chain, "first_damage", firstDamage);
        retained(chain, "first_companion_damage", firstCompanionDamage);
        retained(chain, "lethal", lethal);
        retained(chain, "kill_credit", killCredit);
        retained(chain, "kill_statistic", killStatistic);
        retained(chain, "fight_end", fightEnd);
        retained(chain, "first_death", first.get("death"));
        chain.addProperty("calibration_fixture", calibrationFixture);
        chain.addProperty("calibration_native_chain_complete", calibrationFixture && completeChain() && gaps == 0 && !dead && !ambiguous);
        chain.addProperty("first_death_observed", dead);
        chain.addProperty("observation_gaps", gaps);
        chain.addProperty("critical_evidence_complete", gaps == 0);
        chain.addProperty("attribution_ambiguous", ambiguous);
        chain.addProperty("last_verified_game_tick", lastTick);
        chain.addProperty("post_kill_alive_ticks", postKillAliveTicks);
        chain.add("post_kill_hold_start_game_tick", postKillAliveTicks > 0 ? new JsonPrimitive(fightEnd.get("game_tick").getAsLong() + 1) : JsonNull.INSTANCE);
        chain.add("post_kill_last_verified_game_tick", postKillAliveTicks > 0 ? new JsonPrimitive(lastTick) : JsonNull.INSTANCE);
        chain.addProperty("total_actual_damage", totalActualDamage);
        chain.addProperty("companion_actual_damage", companionActualDamage);
        chain.addProperty("minimum_health", minimumHealth);
        chain.add("current_health", current.get("target_health").deepCopy());
        JsonObject evidence = new JsonObject();
        evidence.add("dragon_combat", chain);
        evidence.add("initial_sample", initialSample.deepCopy());
        evidence.add("current_sample", currentSample.deepCopy());
        evidence.add("armor", previousResources.get("armor").deepCopy());
        evidence.add("inventory", previousResources.get("inventory").deepCopy());
        evidence.add("events", events.deepCopy());
        evidence.addProperty("events_total", eventsTotal);
        evidence.addProperty("events_truncated", eventsTotal > MAX_EVENTS);
        JsonObject coverage = new JsonObject();
        for (String kind : KINDS) {
            JsonObject item = new JsonObject();
            int count = counts.getOrDefault(kind, 0);
            item.addProperty("status", count > 0 ? "observed" : "not_covered");
            item.addProperty("sampled_events", count);
            retained(item, "first_event", first.get(kind));
            coverage.add(kind, item);
        }
        for (String kind : new String[]{"risk_replanning", "combat_replanning", "retreat_decision", "remember_unfinished_goal"}) {
            JsonObject item = new JsonObject();
            item.addProperty("status", "not_measured");
            item.addProperty("reason", "world facts cannot establish planning or decisions; review the tool trace");
            coverage.add(kind, item);
        }
        evidence.add("coverage", coverage);
        facts.add("evidence", evidence);
        return facts;
    }

    /** 主体缺失时只保留已锁存的事实;调用方另记缺口,不能把旧身体引用当当前存活主体。 */
    @Override public JsonObject unavailableSnapshot() { return subject == null ? null : observe(subject); }

    private boolean completeChain() {
        return terminalChain() && lastTick > fightEnd.get("game_tick").getAsLong();
    }

    private boolean terminalChain() {
        return lethal != null && lethal.has("fatal_damage") && killCredit != null && killStatistic != null && fightEnd != null
                && subjectUuid.toString().equals(text(lethal, "credited_actor_uuid"))
                && subjectUuid.toString().equals(text(lethal, "source_actor_uuid"))
                && subjectUuid.toString().equals(text(killCredit, "actor_uuid"))
                && subjectUuid.toString().equals(text(killStatistic, "actor_uuid"))
                && killStatistic.get("before").getAsInt() == 0 && killStatistic.get("after").getAsInt() == 1
                && stats(subject) == 1 && fightEnd.get("dragon_killed").getAsBoolean()
                && fightEnd.get("previously_killed").getAsBoolean() && fightEnd.get("death_animation_ticks").getAsInt() == 200;
    }

    private boolean sameTarget(Entity entity) {
        return targetUuid != null && entity == target && entity.getUUID().equals(targetUuid) && entity.level() == battleLevel;
    }
    private boolean bound() {
        return target != null && !target.isRemoved() && fight != null && target.getDragonFight() == fight
                && targetUuid.equals(fight.getDragonUUID()) && (calibrationFixture || battleLevel.getDragonFight() == fight);
    }
    private JsonObject battle() {
        JsonObject result = identity("server_dragon_battle_sample");
        boolean present = target != null && !target.isRemoved() && battleLevel.getEntity(targetUuid) == target;
        result.addProperty("calibration_fixture", calibrationFixture);
        result.addProperty("fight_bound", fight != null && target != null && target.getDragonFight() == fight
                && targetUuid.equals(fight.getDragonUUID()) && (calibrationFixture || battleLevel.getDragonFight() == fight));
        result.addProperty("needs_state_scanning", fight == null || fight.saveData().needsStateScanning());
        result.addProperty("dragon_killed", fight != null && fight.saveData().dragonKilled());
        result.addProperty("previously_killed", fight != null && fight.saveData().previouslyKilled());
        result.addProperty("is_respawning", fight != null && fight.saveData().isRespawning());
        result.addProperty("target_present", present);
        result.add("target_health", present ? new JsonPrimitive(target.getHealth()) : JsonNull.INSTANCE);
        result.add("target_max_health", target == null ? JsonNull.INSTANCE : new JsonPrimitive(target.getMaxHealth()));
        if (target == null) result.add("target_position", JsonNull.INSTANCE);
        else {
            JsonArray targetPosition = new JsonArray(); targetPosition.add(target.getX()); targetPosition.add(target.getY()); targetPosition.add(target.getZ());
            result.add("target_position", targetPosition);
        }
        result.addProperty("target_no_ai", target != null && target.isNoAi());
        result.addProperty("target_invulnerable", target != null && target.isInvulnerable());
        result.addProperty("phase", target == null ? "missing" : target.getPhaseManager().getCurrentPhase().getPhase().toString());
        result.addProperty("death_animation_ticks", target == null ? 0 : target.dragonDeathTime);
        result.addProperty("dragon_kills", subject == null ? 0 : stats(subject));
        JsonArray crystals = new JsonArray();
        if (battleLevel != null) for (SpikeFeature.EndSpike spike : SpikeFeature.getSpikesForLevel(battleLevel)) {
            for (EndCrystal crystal : battleLevel.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox())) {
                JsonObject item = new JsonObject();
                uuid(item, "uuid", crystal.getUUID());
                JsonArray pos = new JsonArray(); pos.add(crystal.getX()); pos.add(crystal.getY()); pos.add(crystal.getZ());
                item.add("position", pos); item.addProperty("invulnerable", crystal.isInvulnerable());
                item.addProperty("alive", crystal.isAlive());
                crystals.add(item);
            }
        }
        result.add("crystals", crystals);
        result.addProperty("crystals_alive", crystals.size());
        return result;
    }
    private boolean initialCrystalsComplete(JsonArray crystals) {
        if (crystals.size() != 10) return false;
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (JsonElement crystal : crystals) if (crystal.getAsJsonObject().get("invulnerable").getAsBoolean()
                || !crystal.getAsJsonObject().get("alive").getAsBoolean()
                || !identities.add(crystal.getAsJsonObject().get("uuid").getAsString())) return false;
        return true;
    }
    private JsonObject identity(String source) {
        JsonObject result = new JsonObject();
        uuid(result, "subject_uuid", subjectUuid);
        uuid(result, "target_uuid", targetUuid);
        result.add("fight_id", fightId == null ? JsonNull.INSTANCE : new JsonPrimitive(fightId));
        result.addProperty("dimension", battleLevel == null ? "missing" : battleLevel.dimension().location().toString());
        BlockPos origin = target == null ? BlockPos.ZERO : target.getFightOrigin();
        JsonArray pos = new JsonArray(); pos.add(origin.getX()); pos.add(origin.getY()); pos.add(origin.getZ());
        result.add("origin", pos);
        result.addProperty("game_tick", subject == null ? -1 : now());
        result.addProperty("evidence_source", source);
        return result;
    }
    private JsonObject damageFacts(DamageSource source, String evidenceSource) {
        JsonObject result = identity(evidenceSource);
        result.addProperty("damage_type_message_id", source.type().msgId());
        uuid(result, "source_actor_uuid", source.getEntity() == null ? null : source.getEntity().getUUID());
        uuid(result, "direct_entity_uuid", source.getDirectEntity() == null ? null : source.getDirectEntity().getUUID());
        return result;
    }
    private void sampleBody(NumenPlayer body) {
        var loadout = resources.scan(body);
        JsonObject loadoutEvidence = loadout.facts().getAsJsonObject("evidence");
        JsonObject equipment = new JsonObject();
        equipment.add("armor", loadoutEvidence.get("armor").deepCopy());
        equipment.add("inventory", loadoutEvidence.get("inventory").deepCopy());
        Map<String, Integer> inventory = loadout.carried();
        currentSample = identity("server_companion_loadout_sample");
        currentSample.addProperty("health", body.getHealth());
        currentSample.addProperty("food", body.getFoodData().getFoodLevel());
        currentSample.addProperty("saturation", body.getFoodData().getSaturationLevel());
        currentSample.addProperty("dimension", body.level().dimension().location().toString());
        JsonArray currentPosition = new JsonArray(); currentPosition.add(body.getX()); currentPosition.add(body.getY()); currentPosition.add(body.getZ());
        currentSample.add("position", currentPosition);
        currentSample.add("inventory", inventoryCounts(inventory));
        currentSample.add("inventory_slots", equipment.get("inventory").deepCopy());
        currentSample.add("armor", equipment.get("armor").deepCopy());
        currentSample.add("resources", equipment.deepCopy());
        if (previousInventory == null) {
            initialSample = currentSample.deepCopy();
            initialSample.addProperty("evidence_source", "server_companion_initial_loadout");
        } else {
            if (!inventory.equals(previousInventory)) {
                JsonObject event = identity("server_inventory_change_sample");
                event.add("before", inventoryCounts(previousInventory)); event.add("after", inventoryCounts(inventory));
                record("inventory_change", event);
            }
            if (!equipment.equals(previousResources)) {
                JsonObject event = identity("server_equipment_change_sample");
                event.add("before", previousResources.deepCopy()); event.add("after", equipment.deepCopy());
                record("equipment_changed", event);
            }
        }
        previousInventory = inventory;
        previousResources = equipment.deepCopy();
    }
    private void record(String kind, JsonObject event) {
        JsonObject copy = event.deepCopy(); copy.addProperty("kind", kind);
        counts.merge(kind, 1, Integer::sum); first.putIfAbsent(kind, copy.deepCopy());
        eventsTotal++; if (events.size() < MAX_EVENTS) events.add(copy);
    }
    private static JsonObject inventoryCounts(Map<String, Integer> inventory) {
        JsonObject result = new JsonObject(); inventory.forEach(result::addProperty); return result;
    }
    private long now() { return subject.serverLevel().getServer().overworld().getGameTime(); }
    private static int stats(NumenPlayer body) { return body.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON)); }
    private static void uuid(JsonObject result, String key, UUID value) { result.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value.toString())); }
    private static void retained(JsonObject result, String key, JsonObject value) { result.add(key, value == null ? JsonNull.INSTANCE : value.deepCopy()); }
    private static String text(JsonObject object, String key) { return !object.has(key) || object.get(key).isJsonNull() ? null : object.get(key).getAsString(); }
}
