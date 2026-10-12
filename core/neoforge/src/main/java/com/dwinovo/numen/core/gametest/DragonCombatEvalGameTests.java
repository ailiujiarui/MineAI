package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.eval.DragonCombatWorldObserver;
import com.dwinovo.numen.core.mixin.DragonAnimationEvalAccessor;
import com.dwinovo.numen.core.mixin.DragonFightEvalAccessor;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.google.gson.JsonObject;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 第五阶段设施夹具:原生龙战创建+真实hurt链;人工禁AI/阶段/低血/加速动画明确不能当正式或模型成绩。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class DragonCombatEvalGameTests {
    private static final String BATCH = "numen_dragon_combat_eval";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) { settleWorld(level, Difficulty.NORMAL, NOON); }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void native_damage_death_credit_stat_animation_chain_and_tick_commit_barrier(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_native", 200);
        strike(f, f.body, 200);
        JsonObject before = chain(f);
        helper.assertTrue(before.get("lethal").isJsonObject() && before.get("kill_credit").isJsonObject()
                        && before.get("kill_statistic").isJsonObject() && before.get("fight_end").isJsonNull(),
                "actual hurt must produce death/credit/stat before the animation completes: " + before);
        helper.assertTrue(before.getAsJsonObject("kill_statistic").get("before").getAsInt() == 0
                        && before.getAsJsonObject("kill_statistic").get("after").getAsInt() == 1,
                "the target's native killedEntity callback must increment its actor's actual statistic");
        animate(f, 199);
        helper.assertTrue(chain(f).get("fight_end").isJsonNull(), "199 native death ticks are still pending");
        animate(f, 1);
        helper.assertTrue(!chain(f).get("calibration_native_chain_complete").getAsBoolean(),
                "the fight-end tick must close before a success chain is committed");
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            JsonObject facts = WorldEvalObservers.observe(f.body);
            helper.assertTrue(chain(f).get("calibration_native_chain_complete").getAsBoolean(),
                    "all native receipts and the subsequent server tick must agree: " + chain(f));
            helper.assertTrue(!facts.get("native_dragon_kill").getAsBoolean() && !facts.get("dragon_combat_ready").getAsBoolean(),
                    "the public artificial fixture is never a formal benchmark success");
            log("native_chain", f, true);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void actual_damage_is_deduplicated_by_native_event_identity(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_duplicate", 200);
        var source = f.body.damageSources().playerAttack(f.body);
        f.dragon.hurt(f.dragon.head, source, 10);
        JsonObject first = chain(f).getAsJsonObject("first_damage");
        long id = first.get("event_id").getAsLong();
        for (int i = 0; i < 20; i++) WorldEvalObservers.entityDamaged(f.dragon, source, 200, 190, true,
                "native_damage_source", f.body, id);
        helper.assertTrue(chain(f).get("total_actual_damage").getAsFloat() == 10
                        && chain(f).get("companion_actual_damage").getAsFloat() == 10,
                "replayed observations must not duplicate a native damage event: " + chain(f));
        f.dragon.setHealth(200);
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).get("minimum_health").getAsFloat() == 190
                            && chain(f).get("current_health").getAsFloat() == 200
                            && chain(f).get("total_actual_damage").getAsFloat() == 10,
                    "cumulative actual damage, observed minimum and healing/current health remain separate");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void airborne_fatal_damage_restores_one_health_until_native_death_phase_arrives(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_airborne", 200);
        f.dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
        strike(f, f.body, 200);
        helper.assertTrue(f.dragon.getHealth() == 1 && f.dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING
                        && chain(f).get("lethal").isJsonObject() && chain(f).get("fight_end").isJsonNull(),
                "native airborne fatal damage is a real death receipt despite vanilla's subsequent one-health flight");
        // 人工放到原生龙战祭坛上方,然后实际 DragonDeathPhase.doServerTick 自己把血置零。
        BlockPos podium = helper.getLevel().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.world.level.levelgen.feature.EndPodiumFeature.getLocation(f.dragon.getFightOrigin()));
        f.dragon.setPos(podium.getX(), podium.getY(), podium.getZ());
        f.dragon.getPhaseManager().getCurrentPhase().doServerTick();
        helper.assertTrue(f.dragon.getHealth() == 0, "the actual native death phase must enter animation at its target");
        animate(f, 200);
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).get("calibration_native_chain_complete").getAsBoolean(),
                    "the airborne native sequence uses the same independently verified attribution chain");
            log("airborne_native_chain", f, true);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void bed_style_explosion_with_no_actor_cannot_invent_entity_killed_statistic(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_bed_source", 200);
        f.dragon.hurt(f.dragon.head, f.body.damageSources().playerAttack(f.body), 1);
        f.dragon.invulnerableTime = 0;
        f.dragon.hurt(f.dragon.head, f.body.damageSources().badRespawnPointExplosion(f.dragon.position()), 200);
        helper.assertTrue(chain(f).get("lethal").isJsonObject()
                        && chain(f).getAsJsonObject("lethal").get("source_actor_uuid").isJsonNull()
                        && chain(f).get("kill_statistic").isJsonNull()
                        && chain(f).get("attribution_ambiguous").getAsBoolean(),
                "bed-source native explosion may retain last-player kill credit but does not guarantee ENTITY_KILLED: " + chain(f));
        log("bed_source_statistic_limit", f, false);
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void another_actor_native_kill_retains_the_other_credit(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_other", 200);
        NumenPlayer other = spawnAt(helper, "dragon_other_actor", new BlockPos(4, 2, 4), false);
        cleanupBody(helper, other);
        strike(f, other, 200);
        animate(f, 200);
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).getAsJsonObject("kill_credit").get("actor_uuid").getAsString().equals(other.getUUID().toString())
                            && !chain(f).get("calibration_native_chain_complete").getAsBoolean()
                            && !WorldEvalObservers.observe(f.body).get("dragon_damaged").getAsBoolean(),
                    "other actor damage and victory cannot become the subject's progress: " + chain(f));
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void killing_a_non_target_dragon_never_creates_target_receipts(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_nontarget", 200);
        EnderDragon other = EntityType.ENDER_DRAGON.create(helper.getLevel());
        other.setNoAi(true);
        other.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
        other.moveTo(f.dragon.position());
        helper.getLevel().addFreshEntity(other);
        other.hurt(other.head, f.body.damageSources().playerAttack(f.body), 200);
        helper.assertTrue(chain(f).get("lethal").isJsonNull() && chain(f).get("kill_credit").isJsonNull()
                        && chain(f).get("kill_statistic").isJsonNull() && chain(f).get("first_damage").isJsonNull(),
                "same-type foreign dragon cannot fill the frozen target's chain");
        other.discard();
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void command_kill_battle_end_is_not_native_combat_death(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_command_kill", 200);
        f.dragon.kill();
        JsonObject evidence = chain(f);
        helper.assertTrue(evidence.get("lethal").isJsonNull() && evidence.get("kill_credit").isJsonNull()
                        && evidence.getAsJsonObject("fight_end").get("death_animation_ticks").getAsInt() == 0,
                "direct kill may finish the fight but cannot manufacture combat receipts: " + evidence);
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void setting_zero_health_and_advancing_animation_has_no_lethal_damage_receipt(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_set_health", 200);
        f.dragon.setHealth(0);
        animate(f, 200);
        helper.assertTrue(chain(f).get("lethal").isJsonNull() && chain(f).get("kill_statistic").isJsonNull()
                        && chain(f).get("fight_end").isJsonObject(),
                "zero-health manipulation plus animation must remain separate from real damage and statistics");
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void removed_target_is_an_observation_gap_not_a_kill(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_removed", 200);
        f.dragon.discard();
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            JsonObject evidence = chain(f);
            helper.assertTrue(evidence.get("observation_gaps").getAsInt() > 0
                            && !evidence.get("critical_evidence_complete").getAsBoolean()
                            && evidence.get("lethal").isJsonNull() && evidence.get("fight_end").isJsonNull(),
                    "removed, missing or unloaded entities cannot stand in for actual death");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void explicit_observation_gap_and_snapshot_reads_cannot_advance_commit(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_gap", 200);
        strike(f, f.body, 200); animate(f, 200);
        long tick = chain(f).get("last_verified_game_tick").getAsLong();
        for (int i = 0; i < 40; i++) WorldEvalObservers.observe(f.body);
        helper.assertTrue(chain(f).get("last_verified_game_tick").getAsLong() == tick,
                "read-only client snapshots do not represent completed server ticks");
        WorldEvalObservers.observationUnavailable(f.body.getUUID(), helper.getLevel().getGameTime());
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(!chain(f).get("calibration_native_chain_complete").getAsBoolean()
                            && !chain(f).get("critical_evidence_complete").getAsBoolean(),
                    "a later return to visibility cannot erase a missing critical interval");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void first_companion_death_latches_after_body_health_recovers(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_death_latch", 200);
        f.body.hurt(f.body.damageSources().genericKill(), Float.MAX_VALUE);
        f.body.setHealth(20);
        helper.assertTrue(chain(f).get("first_death_observed").getAsBoolean() && chain(f).get("first_death").isJsonObject(),
                "the native death callback remains evidence after the body recovers health");
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void mutual_kill_in_fight_end_tick_latches_death_before_commit(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_mutual", 200);
        strike(f, f.body, 200); animate(f, 200);
        f.body.hurt(f.body.damageSources().genericKill(), Float.MAX_VALUE);
        helper.assertTrue(chain(f).get("first_death_observed").getAsBoolean()
                        && !chain(f).get("calibration_native_chain_complete").getAsBoolean()
                        && chain(f).getAsJsonObject("first_death").get("game_tick").equals(chain(f).getAsJsonObject("fight_end").get("game_tick")),
                "native boss victory cannot bypass the subject's first same-tick death");
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void initial_dead_dragon_and_historical_stat_are_public_invalid_starts(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_invalid_initial", 0, true);
        helper.assertTrue(chain(f).getAsJsonObject("initial_battle").get("target_health").getAsFloat() == 0
                        && chain(f).getAsJsonObject("initial_battle").get("dragon_kills").getAsInt() == 1
                        && chain(f).get("lethal").isJsonNull() && !WorldEvalObservers.observe(f.body).get("dragon_combat_ready").getAsBoolean(),
                "pre-existing death and statistics cannot become current run achievements");
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void native_crystal_nearest_player_fallback_is_ambiguous_attribution(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_crystal_ambiguous", 5);
        EndCrystal crystal = EntityType.END_CRYSTAL.create(helper.getLevel());
        crystal.moveTo(f.body.position());
        helper.getLevel().addFreshEntity(crystal);
        f.dragon.nearestCrystal = crystal;
        f.dragon.onCrystalDestroyed(crystal, f.body.blockPosition(), f.body.damageSources().generic());
        helper.assertTrue(chain(f).get("attribution_ambiguous").getAsBoolean()
                        && chain(f).getAsJsonObject("lethal").get("attribution_origin").getAsString().equals("crystal_nearest_player_fallback")
                        && chain(f).get("companion_actual_damage").getAsFloat() == 0,
                "vanilla nearest-player credit is not evidence that the nearby subject destroyed the crystal: " + chain(f));
        crystal.discard(); helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void explicitly_attributed_crystal_damage_preserves_original_player_source(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_crystal_explicit", 5);
        EndCrystal crystal = EntityType.END_CRYSTAL.create(helper.getLevel());
        crystal.moveTo(f.body.position()); helper.getLevel().addFreshEntity(crystal);
        f.dragon.nearestCrystal = crystal;
        f.dragon.onCrystalDestroyed(crystal, f.body.blockPosition(), f.body.damageSources().playerAttack(f.body));
        helper.assertTrue(!chain(f).get("attribution_ambiguous").getAsBoolean()
                        && chain(f).getAsJsonObject("lethal").get("attribution_origin").getAsString().equals("crystal_explicit_player")
                        && chain(f).getAsJsonObject("lethal").get("provenance_actor_uuid").getAsString().equals(f.body.getUUID().toString()),
                "explicit player crystal source survives vanilla reconstruction without relying on distance");
        crystal.discard(); helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void diagnostic_log_truncation_keeps_critical_receipts_independently(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_truncated", 200);
        for (int i = 0; i < 270; i++) WorldEvalObservers.dimensionChanged(f.body);
        strike(f, f.body, 200); animate(f, 200);
        steps(helper).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            JsonObject facts = WorldEvalObservers.observe(f.body);
            helper.assertTrue(facts.getAsJsonObject("evidence").get("events_truncated").getAsBoolean()
                            && chain(f).get("calibration_native_chain_complete").getAsBoolean()
                            && chain(f).getAsJsonObject("lethal").get("fatal_damage").isJsonObject(),
                    "bounded diagnostics do not erase the independently retained native chain");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void post_kill_survival_counts_exactly_two_hundred_actual_server_ticks(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_hold_200", 200);
        strike(f, f.body, 200); animate(f, 200);
        steps(helper).thenIdle(199).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).get("post_kill_alive_ticks").getAsInt() == 199,
                    "199 ticks after native fight end have not met the user's independent 200-tick survival goal: " + chain(f));
            for (int i = 0; i < 40; i++) WorldEvalObservers.observe(f.body);
            helper.assertTrue(chain(f).get("post_kill_alive_ticks").getAsInt() == 199,
                    "repeated client reads cannot add post-kill survival time");
        }).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            JsonObject evidence = chain(f);
            helper.assertTrue(evidence.get("post_kill_alive_ticks").getAsInt() == 200
                            && evidence.get("post_kill_last_verified_game_tick").getAsLong()
                            - evidence.getAsJsonObject("fight_end").get("game_tick").getAsLong() == 200,
                    "the native terminal battle tick is excluded and each subsequent server tick counts once");
            log("native_chain_with_200_tick_survival", f, true);
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void late_companion_death_permanently_revokes_post_kill_survival(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_hold_death", 200);
        strike(f, f.body, 200); animate(f, 200);
        steps(helper).thenIdle(199).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).get("post_kill_alive_ticks").getAsInt() == 199, "the actual pre-death hold must be 199 ticks");
            f.body.hurt(f.body.damageSources().genericKill(), Float.MAX_VALUE);
            f.body.setHealth(20);
        }).thenIdle(1).thenExecute(() -> {
            WorldEvalObservers.tick(f.body);
            helper.assertTrue(chain(f).get("post_kill_alive_ticks").getAsInt() == 0
                            && chain(f).get("first_death_observed").getAsBoolean()
                            && !chain(f).get("calibration_native_chain_complete").getAsBoolean(),
                    "a recovered body cannot finish the final tick after its first death");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 80, batch = BATCH)
    public static void missing_body_snapshot_retains_first_death_before_body_recovery_and_removal(GameTestHelper helper) {
        Fixture f = fixture(helper, "dragon_death_missing", 200);
        f.body.hurt(f.body.damageSources().genericKill(), Float.MAX_VALUE);
        f.body.setHealth(20);
        CompanionFactory.despawn(helper.getLevel().getServer(), f.body);
        WorldEvalObservers.observationUnavailable(f.body.getUUID(), helper.getLevel().getGameTime());
        JsonObject facts = WorldEvalObservers.observeUnavailable(f.body.getUUID());
        JsonObject evidence = facts.getAsJsonObject("evidence").getAsJsonObject("dragon_combat");
        helper.assertTrue(evidence.get("first_death_observed").getAsBoolean() && evidence.get("first_death").isJsonObject()
                        && !evidence.get("critical_evidence_complete").getAsBoolean()
                        && !facts.get("native_dragon_kill").getAsBoolean(),
                "missing subjects retain irreversible death evidence and cannot borrow a stale alive body");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, String name, float health) { return fixture(helper, name, health, false); }
    private static Fixture fixture(GameTestHelper helper, String name, float health, boolean historicalStat) {
        NumenPlayer body = spawnAt(helper, name, new BlockPos(3, 2, 5), false);
        cleanupBody(helper, body);
        if (historicalStat) body.awardStat(Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON));
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(8, 0, 8));
        EndDragonFight fight = new EndDragonFight(level, level.getSeed(),
                new EndDragonFight.Data(false, false, false, false, Optional.empty(), Optional.empty(), Optional.empty()), origin);
        EnderDragon dragon = ((DragonFightEvalAccessor) fight).numen$createNativeDragon();
        dragon.setNoAi(true);
        dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
        dragon.setHealth(health);
        DragonCombatWorldObserver observer = new DragonCombatWorldObserver(level, fight);
        String id = "dragon-combat-calibration-" + body.getUUID();
        WorldEvalObservers.register(id, () -> observer);
        WorldEvalObservers.start(id, body);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { dragon.discard(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { dragon.discard(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
        });
        return new Fixture(body, dragon, fight, snapshot(body));
    }
    private static void strike(Fixture f, NumenPlayer actor, float amount) {
        f.dragon.hurt(f.dragon.head, actor.damageSources().playerAttack(actor), amount);
    }
    private static void animate(Fixture f, int ticks) {
        for (int i = 0; i < ticks; i++) ((DragonAnimationEvalAccessor) f.dragon).numen$tickNativeDeath();
    }
    private static JsonObject chain(Fixture f) { return WorldEvalObservers.observe(f.body).getAsJsonObject("evidence").getAsJsonObject("dragon_combat"); }
    private static void cleanupBody(GameTestHelper helper, NumenPlayer body) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
            private void cleanup() {
                WorldEvalObservers.stop(body.getUUID());
                if (!body.isRemoved()) CompanionFactory.despawn(helper.getLevel().getServer(), body);
            }
        });
    }
    private static JsonObject snapshot(NumenPlayer body) {
        WorldEvalObservers.tick(body);
        JsonObject result = new JsonObject();
        result.addProperty("companion_uuid", body.getUUID().toString());
        result.addProperty("subject_present", true);
        result.addProperty("alive", body.isAlive());
        result.addProperty("health", body.getHealth());
        result.addProperty("food", body.getFoodData().getFoodLevel());
        result.addProperty("saturation", body.getFoodData().getSaturationLevel());
        result.addProperty("game_tick", body.serverLevel().getServer().overworld().getGameTime());
        result.addProperty("dragon_kills", body.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON)));
        result.addProperty("deaths", body.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        result.addProperty("dimension", body.level().dimension().location().toString());
        com.google.gson.JsonArray position = new com.google.gson.JsonArray(); position.add(body.getX()); position.add(body.getY()); position.add(body.getZ());
        result.add("position", position);
        JsonObject inventory = new JsonObject();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            var stack = body.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        counts.forEach(inventory::addProperty);
        result.add("inventory", inventory);
        result.add("current_task", com.google.gson.JsonNull.INSTANCE);
        result.add("world", WorldEvalObservers.observe(body));
        return result;
    }
    private static void log(String kind, Fixture f, boolean animationAdvanced) {
        JsonObject record = new JsonObject();
        record.addProperty("calibration_fixture", true);
        record.addProperty("real_model", false);
        record.addProperty("artificial_ai_disabled", true);
        record.addProperty("artificial_sitting_phase", true);
        record.addProperty("artificial_death_animation_advanced", animationAdvanced);
        record.addProperty("kind", kind);
        record.add("initial_snapshot", f.initialSnapshot.deepCopy());
        JsonObject current = snapshot(f.body);
        record.add("final_snapshot", current);
        record.add("world", current.get("world").deepCopy());
        Constants.LOG.info("[dragon-combat-calibration] {}", record);
    }
    private record Fixture(NumenPlayer body, EnderDragon dragon, EndDragonFight fight, JsonObject initialSnapshot) {}
}
