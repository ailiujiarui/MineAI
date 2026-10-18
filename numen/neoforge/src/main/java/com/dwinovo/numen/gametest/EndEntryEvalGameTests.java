package com.dwinovo.numen.gametest;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.agent.acceptance.LiveEvalRun;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 第四阶段校准夹具:人工门框及 PortalRoom 元数据只检验原生证据链,不证明长距离寻找能力。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class EndEntryEvalGameTests {
    private static final String BATCH = "numen_end_entry_eval";
    private static final String OBSERVER = "end-entry-v1";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) { settleWorld(level, Difficulty.NORMAL, NOON); }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void eyes_and_public_start_do_not_invent_portal_or_planning_success(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_entry_resources");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 16));
        JsonObject facts = WorldEvalObservers.observe(body);
        assertFlag(helper, facts, "portal_reached", false);
        assertFlag(helper, facts, "portal_activated_by_companion", false);
        assertFlag(helper, facts, "native_end_entry", false);
        JsonObject coverage = facts.getAsJsonObject("evidence").getAsJsonObject("coverage");
        helper.assertTrue(coverage.getAsJsonObject("native_end_entry").get("status").getAsString().equals("not_covered")
                && coverage.getAsJsonObject("remember_unfinished_goal").get("status").getAsString().equals("not_measured"),
                "inventory and location claims cannot manufacture entry or attributed planning evidence");
        helper.succeed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void reached_generated_room_with_unopened_ring_is_not_success(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 0, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_unopened");
        WorldEvalObservers.tick(body);
        steps(helper).thenIdle(2).thenExecute(() -> {
            JsonObject facts = WorldEvalObservers.observe(body);
            assertFlag(helper, facts, "portal_reached", true);
            assertFlag(helper, facts, "portal_activated_by_companion", false);
            assertFlag(helper, facts, "native_end_entry", false);
            helper.assertTrue(!helper.getLevel().getBlockState(center).is(Blocks.END_PORTAL), "unopened portal must remain actual air");
        }).thenSucceed();
    }

    /** 插眼走原版 useItemOn,穿门由放到门平面的身体真实碰撞触发;放位只校准传送,不证明步行。 */
    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void native_eye_activation_and_native_entry_score_after_twenty_alive_ticks(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_native");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        LiveEvalRun run = new LiveEvalRun(spec());
        run.start(1, snapshot(body));
        insertLastEye(helper, body, center);
        helper.assertTrue(helper.getLevel().getBlockState(center).is(Blocks.END_PORTAL), "the vanilla final eye must create real portal blocks");
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for native portal travel"))
                .thenIdle(25).thenExecute(() -> {
                    NumenPlayer current = NumenPlayer.findByUuid(helper.getLevel().getServer(), body.getUUID());
                    helper.assertTrue(current != null && current.getUUID().equals(body.getUUID()) && current.level().dimension() == Level.END,
                            "same subject must remain discoverable in the server player list across dimensions");
                    JsonObject facts = WorldEvalObservers.observe(current);
                    assertFlag(helper, facts, "native_end_entry", true);
                    helper.assertTrue(facts.get("end_alive_held_ticks").getAsInt() >= 20, "native entry must use per-server-tick alive hold");
                    helper.assertTrue(facts.get("ender_eyes").getAsInt() == 0, "legitimate final-eye consumption cannot revoke entry success");
                    JsonObject chain = chain(facts);
                    helper.assertTrue(chain.getAsJsonObject("portal").get("initial_eyes").getAsInt() == 11
                                    && chain.getAsJsonObject("activation").get("eye_inventory_delta").getAsInt() == -1
                                    && chain.getAsJsonObject("entry").get("evidence_source").getAsString().equals("vanilla_handle_portal_change_dimension")
                                    && chain.getAsJsonObject("entry").get("destination_uuid").getAsString().equals(body.getUUID().toString()),
                            "initial generated eyes, native final-eye decrement and same-UUID portal invocation must be reviewable: " + chain);
                    run.observe(2, snapshot(current));
                    helper.assertTrue(run.finished() && run.report().get("status").getAsString().equals("success"),
                            "actual observer facts must satisfy the generic stage-four grader: " + run.report());
                    JsonObject evidence = new JsonObject();
                    evidence.addProperty("calibration_fixture", true);
                    evidence.addProperty("real_model", false);
                    evidence.addProperty("capability_baseline", false);
                    evidence.add("result", run.report());
                    Constants.LOG.info("[end-entry-calibration] {}", evidence);
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void initially_open_portal_is_observed_without_claiming_companion_activation(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 12, true, true);
        NumenPlayer body = observedBody(helper, "gametest_entry_already_open");
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for native preopened entry"))
                .thenIdle(3).thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "portal_activated_by_companion", false);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(chain(facts).getAsJsonObject("portal").get("initial_active").getAsBoolean()
                                    && chain(facts).get("activation").isJsonNull() && chain(facts).get("entry").isJsonObject(),
                            "preopened native entry may be recorded but cannot invent an activation achievement");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void another_actor_inserting_an_eye_prevents_companion_only_activation(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 10, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_assisted");
        NumenPlayer other = spawnAt(helper, "gametest_entry_other", new BlockPos(6, 2, 6), false);
        cleanupBody(helper, other);
        other.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertEye(helper, other, frames(center)[10]);
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for assisted native entry"))
                .thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "portal_activated_by_companion", false);
                    assertFlag(helper, facts, "native_end_entry", false);
                    JsonObject portal = chain(facts).getAsJsonObject("portal");
                    helper.assertTrue(portal.get("other_eye_insertions").getAsInt() == 1
                                    && portal.get("companion_eye_insertions").getAsInt() == 1,
                            "assistance and the final companion action must remain distinct: " + portal);
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void activated_first_portal_cannot_be_joined_to_entry_through_second_portal(GameTestHelper helper) {
        BlockPos first = fixture(helper, new BlockPos(6, 2, 6), 11, true, false);
        BlockPos second = fixture(helper, new BlockPos(15, 2, 15), 12, false, true);
        NumenPlayer body = observedBody(helper, "gametest_entry_two_portals");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, first);
        dropIntoPortal(body, second);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for second portal native entry"))
                .thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(chain(facts).get("activation").isJsonNull()
                                    && chain(facts).getAsJsonObject("portal").getAsJsonArray("position").get(0).getAsInt() == second.getX(),
                            "selected entry must retain the second portal identity rather than borrowing the first activation");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void directly_changing_dimension_cannot_claim_native_portal_entry(GameTestHelper helper) {
        NumenPlayer body = observedBody(helper, "gametest_entry_direct");
        change(body, helper.getLevel().getServer().getLevel(Level.END), new Vec3(100.5, 50, 0.5));
        steps(helper).thenIdle(25).thenExecute(() -> {
            JsonObject facts = WorldEvalObservers.observe(body);
            assertFlag(helper, facts, "native_end_entry", false);
            helper.assertTrue(facts.get("end_alive_held_ticks").getAsInt() == 0 && chain(facts).get("entry").isJsonNull(),
                    "ordinary dimension-change receipt cannot create portal processor evidence or alive hold");
        }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void artificial_ring_without_stronghold_metadata_is_only_route_calibration(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, false, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_artificial");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for artificial native entry"))
                .thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(chain(facts).get("calibration_native_end_entry").getAsBoolean()
                                    && !chain(facts).getAsJsonObject("portal").get("generated_stronghold").getAsBoolean(),
                            "native route evidence alone cannot claim that a generated stronghold was found");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 200, batch = BATCH)
    public static void observation_gap_is_permanent_and_duplicate_snapshots_do_not_advance_hold(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_gap");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for native entry before gap"))
                .thenIdle(3).thenExecute(() -> {
                    int before = WorldEvalObservers.observe(body).get("end_alive_held_ticks").getAsInt();
                    for (int i = 0; i < 40; i++) WorldEvalObservers.observe(body);
                    helper.assertTrue(WorldEvalObservers.observe(body).get("end_alive_held_ticks").getAsInt() == before,
                            "client reads cannot count as server ticks");
                    WorldEvalObservers.observationUnavailable(body.getUUID(), helper.getLevel().getGameTime());
                }).thenIdle(25).thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "observation_complete", false);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(facts.get("end_alive_held_ticks").getAsInt() == 0 && chain(facts).get("observation_gaps").getAsInt() > 0,
                            "returned visibility cannot erase an unobserved interval");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void leaving_end_then_direct_return_cannot_reuse_a_previous_native_episode(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_episode");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for first native episode"))
                .thenIdle(3).thenExecute(() -> {
                    change(body, helper.getLevel(), helper.absoluteVec(new Vec3(4.5, 2, 4.5)));
                    change(body, helper.getLevel().getServer().getLevel(Level.END), new Vec3(100.5, 50, 0.5));
                }).thenIdle(25).thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(facts.get("end_alive_held_ticks").getAsInt() == 0 && chain(facts).get("entry").isJsonObject()
                                    && !chain(facts).get("native_entry_episode_active").getAsBoolean(),
                            "historical native entry remains evidence but cannot authenticate a later direct teleport");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void first_death_revokes_native_entry_hold(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_dead");
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for native entry before death"))
                .thenIdle(70).thenExecute(() -> body.hurt(body.damageSources().genericKill(), Float.MAX_VALUE))
                .thenIdle(2).thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "native_end_entry", false);
                    helper.assertTrue(chain(facts).get("first_death_observed").getAsBoolean()
                                    && facts.get("end_alive_held_ticks").getAsInt() == 0,
                            "first actual death must latch and erase the alive hold");
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 250, batch = BATCH)
    public static void diagnostic_history_truncation_keeps_the_native_chain_independently(GameTestHelper helper) {
        BlockPos center = fixture(helper, new BlockPos(7, 2, 7), 11, true, false);
        NumenPlayer body = observedBody(helper, "gametest_entry_history");
        for (int i = 0; i < 140; i++) WorldEvalObservers.dimensionChanged(body);
        body.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE));
        insertLastEye(helper, body, center);
        dropIntoPortal(body, center);
        steps(helper).thenWaitUntil(() -> helper.assertTrue(body.level().dimension() == Level.END, "wait for native entry after history fills"))
                .thenIdle(25).thenExecute(() -> {
                    JsonObject facts = WorldEvalObservers.observe(body);
                    assertFlag(helper, facts, "native_end_entry", true);
                    helper.assertTrue(facts.getAsJsonObject("evidence").get("events_truncated").getAsBoolean()
                                    && chain(facts).get("critical_evidence_complete").getAsBoolean()
                                    && chain(facts).get("arrival").isJsonObject() && chain(facts).get("activation").isJsonObject()
                                    && chain(facts).get("entry").isJsonObject(),
                            "bounded diagnostic logs must not erase separately retained critical portal evidence");
                }).thenSucceed();
    }

    private static JsonObject spec() {
        return JsonParser.parseString("""
                {"evaluation_version":"end-entry-v1","world_observer":"end-entry-v1","case_id":"live.end-entry",
                 "prompt":"进入末地","seed":42,"world_preset":"normal","setup":{},"success_hold_ticks":20,
                 "success":{"world":{"native_end_entry":true},"min_health":16,"min_food":16},
                 "budget":{"max_model_calls":1,"max_seconds":100,"max_tokens":100}}
                """).getAsJsonObject();
    }
    private static JsonObject snapshot(NumenPlayer body) {
        // 与真实客户端快照一样验证当前服务端tick;观察器去重,重复读取不推进保持。
        WorldEvalObservers.tick(body);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("alive", body.isAlive());
        snapshot.addProperty("subject_present", true);
        snapshot.addProperty("companion_uuid", body.getUUID().toString());
        snapshot.addProperty("deaths", body.isAlive() ? 0 : 1);
        snapshot.addProperty("health", body.getHealth());
        snapshot.addProperty("food", body.getFoodData().getFoodLevel());
        snapshot.addProperty("dragon_kills", 0);
        snapshot.addProperty("game_tick", body.serverLevel().getServer().overworld().getGameTime());
        snapshot.addProperty("dimension", body.level().dimension().location().toString());
        snapshot.add("inventory", new JsonObject());
        JsonArray position = new JsonArray();
        position.add(body.getX()); position.add(body.getY()); position.add(body.getZ());
        snapshot.add("position", position);
        snapshot.add("world", WorldEvalObservers.observe(body));
        return snapshot;
    }
    private static JsonObject chain(JsonObject facts) { return facts.getAsJsonObject("evidence").getAsJsonObject("end_entry"); }
    private static void assertFlag(GameTestHelper helper, JsonObject facts, String key, boolean expected) {
        helper.assertTrue(facts.get(key).getAsBoolean() == expected, key + " must be " + expected + ": " + facts);
    }
    private static void insertLastEye(GameTestHelper helper, NumenPlayer body, BlockPos center) { insertEye(helper, body, frames(center)[11]); }
    private static void insertEye(GameTestHelper helper, NumenPlayer body, BlockPos frame) {
        body.gameMode.useItemOn(body, helper.getLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(frame.getCenter(), Direction.UP, frame, false));
    }
    // 同维度放位需同步原版待确认位置,否则首刻登录回执会把身体拉回出生点。
    private static void dropIntoPortal(NumenPlayer body, BlockPos center) { body.teleportTo(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5); }
    private static void change(NumenPlayer body, ServerLevel destination, Vec3 position) {
        body.changeDimension(new DimensionTransition(destination, position, Vec3.ZERO, 0, 0, DimensionTransition.DO_NOTHING));
    }
    private static BlockPos[] frames(BlockPos center) {
        BlockPos[] frames = new BlockPos[12];
        int n = 0;
        for (int offset = -1; offset <= 1; offset++) {
            frames[n++] = center.offset(offset, 0, -2);
            frames[n++] = center.offset(offset, 0, 2);
            frames[n++] = center.offset(-2, 0, offset);
            frames[n++] = center.offset(2, 0, offset);
        }
        return frames;
    }
    private static BlockPos fixture(GameTestHelper helper, BlockPos relative, int initialEyes, boolean metadata, boolean active) {
        BlockPos center = helper.absolutePos(relative);
        BlockPos[] frames = frames(center);
        Direction[] facing = {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};
        for (int i = 0; i < frames.length; i++) {
            BlockState state = Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing[i % 4])
                    .setValue(EndPortalFrameBlock.HAS_EYE, i < initialEyes);
            helper.getLevel().setBlockAndUpdate(frames[i], state);
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            helper.getLevel().setBlockAndUpdate(center.offset(dx, 0, dz), active ? Blocks.END_PORTAL.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        if (metadata) metadata(helper, center);
        return center;
    }
    /** 合法结构元数据也必须经真实 StructureManager 检查,测试不直接设置观察器的成功标。 */
    private static void metadata(GameTestHelper helper, BlockPos center) {
        ServerLevel level = helper.getLevel();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BuiltinStructures.STRONGHOLD);
        LevelChunk chunk = level.getChunkAt(center);
        Map<Structure, StructureStart> starts = new HashMap<>(chunk.getAllStarts());
        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references = new HashMap<>(chunk.getAllReferences());
        StrongholdPieces.PortalRoom room = new StrongholdPieces.PortalRoom(0,
                new BoundingBox(center.getX() - 3, center.getY() - 1, center.getZ() - 3,
                        center.getX() + 3, center.getY() + 4, center.getZ() + 3), Direction.NORTH);
        ChunkPos startChunk = chunk.getPos();
        chunk.setStartForStructure(structure, new StructureStart(structure, startChunk, 0, new PiecesContainer(List.of(room))));
        chunk.addReferenceForStructure(structure, startChunk.toLong());
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
            private void cleanup() { chunk.setAllStarts(starts); chunk.setAllReferences(references); }
        });
    }
    private static NumenPlayer observedBody(GameTestHelper helper, String name) {
        NumenPlayer body = spawnAt(helper, name, new BlockPos(3, 2, 5), false);
        body.getInventory().clearContent();
        cleanupBody(helper, body);
        WorldEvalObservers.start(OBSERVER, body);
        return body;
    }
    private static void cleanupBody(GameTestHelper helper, NumenPlayer body) {
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
    }
}
