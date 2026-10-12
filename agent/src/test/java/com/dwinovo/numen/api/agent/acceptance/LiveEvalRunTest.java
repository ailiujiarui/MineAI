package com.dwinovo.numen.api.agent.acceptance;

import com.dwinovo.numen.api.agent.provider.Usage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveEvalRunTest {
    private static final long START = 10_000_000_000L;
    private static final String SUBJECT = "d290f1ee-6c54-4b01-90e6-d701748f0851";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject spec() {
        return json("""
                {"evaluation_version":"live-v1","case_id":"survival-start","prompt":"准备工作台和木镐",
                 "seed":42,"world_preset":"flat","setup":{},
                 "success":{"items":{"minecraft:crafting_table":1,"minecraft:wooden_pickaxe":1}},
                 "milestones":[{"id":"first-log","condition":{"items":{"minecraft:oak_log":1}}}],
                 "budget":{"max_model_calls":2,"max_seconds":10,"max_tokens":100}}
                """);
    }

    private static JsonObject snapshot() {
        return json("""
                {"alive":true,"deaths":0,"inventory":{},"health":20,"food":20,
                 "dragon_kills":0,"position":[0,64,0]}
                """);
    }

    private static JsonObject completed() {
        JsonObject result = snapshot();
        result.add("inventory", json("{\"minecraft:crafting_table\":1,\"minecraft:wooden_pickaxe\":1}"));
        return result;
    }

    private static LiveEvalRun started(JsonObject spec) {
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, snapshot());
        return run;
    }

    private static void outcome(LiveEvalRun run, String status, String reason) {
        assertTrue(run.finished());
        assertEquals(status, run.report().get("status").getAsString());
        assertEquals(reason, run.report().get("reason").getAsString());
    }

    private static JsonObject survivalSpec() {
        JsonObject spec = spec();
        spec.addProperty("evaluation_version", "survival-v1");
        spec.addProperty("world_observer", "sustainable-survival-v1");
        spec.addProperty("success_hold_ticks", 40);
        spec.add("success", json("""
                {"world":{"renewable_food":true,"harvested_food":4},"min_health":10,"min_food":12}
                """));
        spec.remove("milestones");
        return spec;
    }

    private static JsonObject survivalSnapshot(long tick, boolean renewable, int harvested) {
        JsonObject snapshot = snapshot();
        snapshot.addProperty("game_tick", tick);
        JsonObject world = new JsonObject();
        world.addProperty("renewable_food", renewable);
        world.addProperty("harvested_food", harvested);
        snapshot.add("world", world);
        return snapshot;
    }

    @Test
    void freeSurvivalProtocolScoresGenericWorldFactsWithoutAgriculturalRequirements() {
        JsonObject spec = survivalSpec();
        spec.addProperty("evaluation_version", "survival-v2");
        spec.addProperty("world_observer", "free-survival-v2");
        spec.add("success", json("""
                {"world":{"food_nutrition":20,"shelter":true},"min_health":10,"min_food":12}
                """));
        JsonObject initial = snapshot();
        initial.addProperty("game_tick", 100);
        initial.add("world", json("{\"food_nutrition\":19,\"shelter\":true}"));
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, initial);
        assertFalse(run.finished());
        JsonObject ready = initial.deepCopy();
        ready.getAsJsonObject("world").addProperty("food_nutrition", 20);
        for (long tick : new long[]{120, 140, 160}) {
            ready.addProperty("game_tick", tick);
            run.observe(START + tick, ready);
            if (tick < 160) assertFalse(run.finished());
        }
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(40, run.report().get("success_held_ticks").getAsLong());
        assertEquals("survival-v2", run.report().get("evaluation_version").getAsString());
        assertEquals(spec, run.report().getAsJsonObject("spec"));
    }

    @Test
    void expeditionPreparationRequiresCurrentSuppliesAndWorldFactsThroughoutTheHold() {
        JsonObject spec = survivalSpec();
        spec.addProperty("evaluation_version", "expedition-v1");
        spec.addProperty("world_observer", "expedition-v1");
        spec.add("success", json("""
                {"world":{"equipment_ready":true,"food_nutrition":48},
                 "items":{"minecraft:crafting_table":1},"min_health":10,"min_food":12}
                """));
        spec.add("milestones", JsonParser.parseString("""
                [{"id":"equipment","condition":{"world":{"equipment_ready":true}}},
                 {"id":"food","condition":{"world":{"food_nutrition":48}}}]
                """));
        JsonObject state = snapshot();
        state.addProperty("game_tick", 100);
        state.add("world", json("{\"equipment_ready\":false,\"food_nutrition\":0}"));
        state.add("inventory", json("{\"minecraft:crafting_table\":1}"));
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, state);
        state.addProperty("game_tick", 120);
        state.getAsJsonObject("world").addProperty("equipment_ready", true);
        run.observe(START + 1, state);
        state.addProperty("game_tick", 140);
        state.getAsJsonObject("world").addProperty("equipment_ready", false);
        state.getAsJsonObject("world").addProperty("food_nutrition", 48);
        run.observe(START + 2, state);
        assertEquals(2, run.report().getAsJsonArray("milestones").size());
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        assertFalse(run.finished(), "historical milestones cannot replace a simultaneously ready expedition loadout");
        state.getAsJsonObject("world").addProperty("equipment_ready", true);
        for (long tick : new long[]{160, 180}) {
            state.addProperty("game_tick", tick);
            run.observe(START + tick, state);
        }
        assertEquals(20, run.report().get("success_held_ticks").getAsLong());
        state.addProperty("game_tick", 200);
        state.add("inventory", new JsonObject());
        run.observe(START + 200, state);
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        assertFalse(run.finished(), "losing a portable workstation resets the entire hold");
        state.add("inventory", json("{\"minecraft:crafting_table\":1}"));
        for (long tick : new long[]{220, 240, 260}) {
            state.addProperty("game_tick", tick);
            run.observe(START + tick, state);
            if (tick < 260) assertFalse(run.finished());
        }
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(40, run.report().get("success_held_ticks").getAsLong());
        assertEquals("expedition-v1", run.report().get("evaluation_version").getAsString());
        assertEquals(spec, run.report().getAsJsonObject("spec"));
    }

    @Test
    void originalObserverMappingsRemainAvailableAlongsideExpedition() {
        for (String[] pair : new String[][]{{"live-v1", "sustainable-survival-v1"},
                {"survival-v1", "sustainable-survival-v1"}, {"survival-v2", "free-survival-v2"},
                {"expedition-v1", "expedition-v1"}, {"end-resources-v1", "end-resources-v1"}}) {
            JsonObject spec = spec();
            spec.addProperty("evaluation_version", pair[0]);
            spec.addProperty("world_observer", pair[1]);
            assertEquals(spec, new LiveEvalRun(spec).report().getAsJsonObject("spec"));
            spec.remove("world_observer");
            assertEquals(spec, new LiveEvalRun(spec).report().getAsJsonObject("spec"));
        }
    }

    @Test
    void unknownProtocolVersionsAndObserversAreRejected() {
        for (String version : new String[]{"live-v2", "survival-v0", "survival-v3", "expedition-v2", "end-resources-v2", ""}) {
            JsonObject spec = spec();
            spec.addProperty("evaluation_version", version);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), version);
        }
        for (String version : new String[]{"live-v1", "survival-v1", "survival-v2", "expedition-v1", "end-resources-v1"}) {
            for (String observer : new String[]{"unknown", "sustainable-survival-v2", "free-survival-v1", ""}) {
                JsonObject spec = spec();
                spec.addProperty("evaluation_version", version);
                spec.addProperty("world_observer", observer);
                assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), version + ":" + observer);
            }
        }
    }

    @Test
    void observersCannotReinterpretAnotherProtocolVersion() {
        for (String[] pair : new String[][]{{"live-v1", "free-survival-v2"},
                {"survival-v1", "free-survival-v2"}, {"survival-v2", "sustainable-survival-v1"},
                {"live-v1", "expedition-v1"}, {"survival-v1", "expedition-v1"},
                {"survival-v2", "expedition-v1"}, {"expedition-v1", "sustainable-survival-v1"},
                {"expedition-v1", "free-survival-v2"}}) {
            JsonObject spec = spec();
            spec.addProperty("evaluation_version", pair[0]);
            spec.addProperty("world_observer", pair[1]);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), pair[0] + ":" + pair[1]);
        }
    }

    private static JsonObject endEntrySpec() {
        JsonObject spec = spec();
        spec.addProperty("evaluation_version", "end-entry-v1");
        spec.addProperty("world_observer", "end-entry-v1");
        spec.addProperty("case_id", "live.end-entry");
        spec.addProperty("success_hold_ticks", 20);
        spec.add("setup", json("{\"equipment\":{\"head\":\"minecraft:iron_helmet\",\"offhand\":\"minecraft:shield\"}}"));
        spec.add("success", json("{\"world\":{\"native_end_entry\":true},\"min_health\":16,\"min_food\":16}"));
        spec.add("milestones", JsonParser.parseString("""
                [{"id":"portal-reached","condition":{"world":{"portal_reached":true}}},
                 {"id":"portal-activated","condition":{"world":{"portal_activated_by_companion":true}}},
                 {"id":"native-end-entry","condition":{"world":{"native_end_entry":true}}}]
                """));
        return spec;
    }

    private static JsonObject endEntrySnapshot(long tick) {
        JsonObject state = snapshot();
        state.addProperty("game_tick", tick);
        state.addProperty("companion_uuid", SUBJECT);
        state.addProperty("subject_present", true);
        state.addProperty("dimension", "minecraft:overworld");
        JsonObject world = json("""
                {"portal_reached":false,"portal_activated_by_companion":false,"native_end_entry":false,
                 "end_alive_held_ticks":0,"observation_complete":true,
                 "evidence":{"events_truncated":false,"end_entry":{"portal":null,"arrival":null,"activation":null,
                  "entry":null,"observation_gaps":0,"critical_evidence_complete":true,"native_entry_episode_active":false,
                  "first_death_observed":false}}}
                """);
        world.addProperty("subject_uuid", SUBJECT);
        JsonObject chain = world.getAsJsonObject("evidence").getAsJsonObject("end_entry");
        chain.addProperty("subject_uuid", SUBJECT);
        chain.addProperty("last_verified_game_tick", tick);
        state.add("world", world);
        return state;
    }

    private static JsonObject portal(boolean active) {
        JsonObject portal = json("""
                {"portal_id":"minecraft:overworld:0:64:0","dimension":"minecraft:overworld","x":0,"y":64,"z":0,
                 "generated_stronghold":true,"portal_origin":"generated_stronghold","initial_eyes":10,
                 "initial_active":false,"other_eye_insertions":0}
                """);
        portal.addProperty("active", active);
        portal.addProperty("eyes", active ? 12 : 10);
        portal.addProperty("companion_eye_insertions", active ? 2 : 0);
        JsonArray frames = new JsonArray();
        for (String facing : new String[]{"south", "north", "east", "west"}) {
            for (int offset = -1; offset <= 1; offset++) {
                JsonObject frame = json("{\"y\":64,\"id\":\"minecraft:end_portal_frame\"}");
                frame.addProperty("x", "east".equals(facing) ? -2 : "west".equals(facing) ? 2 : offset);
                frame.addProperty("z", "south".equals(facing) ? -2 : "north".equals(facing) ? 2 : offset);
                frame.addProperty("facing", facing);
                frame.addProperty("has_eye", active || frames.size() < 10);
                frames.add(frame);
            }
        }
        portal.add("frames", frames);
        JsonArray blocks = new JsonArray();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                JsonObject block = json("{\"y\":64}");
                block.addProperty("x", x);
                block.addProperty("z", z);
                block.addProperty("id", active ? "minecraft:end_portal" : "minecraft:air");
                blocks.add(block);
            }
        }
        portal.add("portal_blocks", blocks);
        return portal;
    }

    private static JsonObject portalEvent(String kind, long tick, boolean active) {
        JsonObject event = json("{\"dimension\":\"minecraft:overworld\",\"x\":0,\"y\":64,\"z\":0}");
        event.addProperty("kind", kind);
        event.addProperty("subject_uuid", SUBJECT);
        event.addProperty("game_tick", tick);
        event.addProperty("portal_id", "minecraft:overworld:0:64:0");
        event.add("portal", portal(active));
        return event;
    }

    private static JsonObject endEntryChain(JsonObject state) {
        return state.getAsJsonObject("world").getAsJsonObject("evidence").getAsJsonObject("end_entry");
    }

    private static JsonObject nativeEndEntrySnapshot(long tick, int heldTicks) {
        JsonObject state = endEntrySnapshot(tick);
        state.addProperty("dimension", "minecraft:the_end");
        JsonObject world = state.getAsJsonObject("world");
        world.addProperty("portal_reached", true);
        world.addProperty("portal_activated_by_companion", true);
        world.addProperty("native_end_entry", true);
        world.addProperty("end_alive_held_ticks", heldTicks);
        JsonObject chain = endEntryChain(state);
        chain.addProperty("native_entry_episode_active", true);
        chain.add("portal", portal(true));
        chain.add("arrival", portalEvent("portal_reached", 100, false));
        JsonObject activation = portalEvent("portal_activated", 105, true);
        activation.addProperty("actor_uuid", SUBJECT);
        activation.addProperty("native_eye_inserted", true);
        activation.addProperty("eye_inventory_delta", -1);
        activation.add("frame", json("{\"x\":2,\"y\":64,\"z\":1}"));
        chain.add("activation", activation);
        JsonObject entry = portalEvent("native_end_entry", 110, true);
        entry.addProperty("destination_uuid", SUBJECT);
        entry.addProperty("dimension", "minecraft:the_end");
        entry.addProperty("from_dimension", "minecraft:overworld");
        entry.addProperty("to_dimension", "minecraft:the_end");
        entry.addProperty("evidence_source", "vanilla_handle_portal_change_dimension");
        entry.add("source_block", json("{\"x\":0,\"y\":64,\"z\":0}"));
        chain.add("entry", entry);
        return state;
    }

    private static LiveEvalRun startedEndEntry() {
        LiveEvalRun run = new LiveEvalRun(endEntrySpec());
        JsonObject initial = endEntrySnapshot(90);
        initial.add("inventory", json("{\"minecraft:ender_eye\":8,\"minecraft:ender_pearl\":8,\"minecraft:blaze_rod\":4}"));
        run.start(START, initial);
        return run;
    }

    @Test
    void endEntryProtocolPairsItsObserverAndRequiresNativeEvidenceRatherThanAnInventoryCriterion() {
        assertEquals(endEntrySpec(), new LiveEvalRun(endEntrySpec()).report().getAsJsonObject("spec"));
        JsonObject spec = endEntrySpec();
        spec.remove("world_observer");
        assertEquals(spec, new LiveEvalRun(spec).report().getAsJsonObject("spec"));
        for (String observer : new String[]{"end-resources-v1", "expedition-v1", "free-survival-v2"}) {
            spec.addProperty("world_observer", observer);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec));
        }
        spec.addProperty("world_observer", "end-entry-v1");
        spec.add("success", json("{\"items\":{\"minecraft:ender_eye\":16}}"));
        assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec));
    }

    @Test
    void eyesCoordinatesFramesAndAssistantClaimsCannotBecomeEndEntrySuccess() {
        LiveEvalRun run = startedEndEntry();
        run.event("assistant", json("{\"text\":\"已经找到要塞并进入末地\"}"), START + 1);
        run.event("tool_result", json("{\"position\":[0,64,0]}"), START + 2);
        JsonObject state = endEntrySnapshot(120);
        state.add("inventory", json("{\"minecraft:ender_eye\":16}"));
        endEntryChain(state).add("portal", portal(false));
        for (String key : new String[]{"portal_reached", "portal_activated_by_companion", "native_end_entry"}) {
            state.getAsJsonObject("world").addProperty(key, true);
        }
        run.observe(START + 3, state);
        assertFalse(run.finished());
        assertEquals(0, run.report().getAsJsonArray("milestones").size(), "boolean claims need the independent chain");
        assertThrows(IllegalArgumentException.class, () -> run.finish("success", "claim", START + 4));
    }

    @Test
    void completeNativeChainAllowsConsumedEyesAndRetainsHistoricalMilestonesAcrossDimensions() {
        LiveEvalRun run = startedEndEntry();
        JsonObject state = nativeEndEntrySnapshot(129, 20);
        state.add("inventory", new JsonObject());
        state.getAsJsonObject("world").getAsJsonObject("evidence").addProperty("events_truncated", true);
        run.observe(START + 1, state);
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(20, run.report().get("success_held_ticks").getAsLong());
        assertEquals(3, run.report().getAsJsonArray("milestones").size());
        assertEquals(SUBJECT, run.report().getAsJsonObject("final_snapshot").get("companion_uuid").getAsString());
        assertEquals("minecraft:overworld", run.report().getAsJsonObject("initial_snapshot").get("dimension").getAsString());
        assertEquals("minecraft:the_end", run.report().getAsJsonObject("final_snapshot").get("dimension").getAsString());
        assertTrue(run.report().getAsJsonObject("final_snapshot").getAsJsonObject("inventory").isEmpty());
    }

    @Test
    void portalStateIdentityActorNativeSourceAndSequenceCannotBeForgedByPositiveFlags() {
        java.util.List<java.util.function.Consumer<JsonObject>> corruptions = java.util.List.of(
                state -> endEntryChain(state).getAsJsonObject("activation").getAsJsonObject("portal").addProperty("active", false),
                state -> endEntryChain(state).getAsJsonObject("entry").getAsJsonObject("portal").getAsJsonArray("portal_blocks")
                        .get(0).getAsJsonObject().addProperty("id", "minecraft:air"),
                state -> endEntryChain(state).getAsJsonObject("activation").getAsJsonObject("portal").getAsJsonArray("frames")
                        .get(0).getAsJsonObject().addProperty("facing", "north"),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("portal_id", "minecraft:overworld:1:64:0"),
                state -> endEntryChain(state).getAsJsonObject("arrival").addProperty("subject_uuid", "another-subject"),
                state -> endEntryChain(state).getAsJsonObject("activation").addProperty("subject_uuid", "another-subject"),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("subject_uuid", "another-subject"),
                state -> endEntryChain(state).getAsJsonObject("activation").addProperty("actor_uuid", "another-subject"),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("destination_uuid", "another-subject"),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("evidence_source", "direct_change_dimension"),
                state -> endEntryChain(state).addProperty("native_entry_episode_active", false),
                state -> endEntryChain(state).getAsJsonObject("entry").add("source_block", json("{\"x\":7,\"y\":64,\"z\":0}")),
                state -> endEntryChain(state).getAsJsonObject("activation").add("frame", json("{\"x\":0,\"y\":64,\"z\":0}")),
                state -> endEntryChain(state).getAsJsonObject("activation").addProperty("eye_inventory_delta", 0),
                state -> endEntryChain(state).getAsJsonObject("activation").addProperty("native_eye_inserted", false),
                state -> endEntryChain(state).getAsJsonObject("arrival").addProperty("game_tick", 106),
                state -> endEntryChain(state).getAsJsonObject("arrival").addProperty("game_tick", 89),
                state -> endEntryChain(state).getAsJsonObject("arrival").addProperty("x", 7),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("from_dimension", "minecraft:the_nether"),
                state -> endEntryChain(state).getAsJsonObject("entry").addProperty("game_tick", 130));
        for (var corruption : corruptions) {
            LiveEvalRun run = startedEndEntry();
            JsonObject state = nativeEndEntrySnapshot(129, 20);
            corruption.accept(state);
            run.observe(START + 1, state);
            assertFalse(run.finished(), run.report().toString());
            assertThrows(IllegalArgumentException.class, () -> run.finish("success", "flags", START + 2));
        }
    }

    @Test
    void missingNativeActorAndDestinationIdentityCannotAuthenticateEndEntry() {
        for (String field : new String[]{"actor_uuid", "destination_uuid"}) {
            LiveEvalRun run = startedEndEntry();
            JsonObject state = nativeEndEntrySnapshot(129, 20);
            endEntryChain(state).getAsJsonObject("actor_uuid".equals(field) ? "activation" : "entry").remove(field);
            run.observe(START + 1, state);
            assertEquals("infra_error", run.report().get("status").getAsString());
            assertTrue(run.report().get("reason").getAsString().contains(field));
        }
    }

    @Test
    void firstObservedDeathCannotBeHiddenByLivingSnapshotAndUnchangedDeathStatistics() {
        LiveEvalRun run = startedEndEntry();
        JsonObject state = nativeEndEntrySnapshot(129, 20);
        endEntryChain(state).addProperty("first_death_observed", true);
        run.observe(START + 1, state);
        outcome(run, "failed", "companion_died");
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
    }

    @Test
    void preopenedArtificialOrOtherActorAssistedPortalsCannotBecomeCompanionActivation() {
        for (String mode : new String[]{"preopened", "other-actor", "fixture", "insertion-count"}) {
            LiveEvalRun run = startedEndEntry();
            JsonObject state = nativeEndEntrySnapshot(129, 20);
            for (String key : new String[]{"portal", "arrival", "activation", "entry"}) {
                JsonObject portal = "portal".equals(key) ? endEntryChain(state).getAsJsonObject(key)
                        : endEntryChain(state).getAsJsonObject(key).getAsJsonObject("portal");
                switch (mode) {
                    case "preopened" -> portal.addProperty("initial_active", true);
                    case "other-actor" -> portal.addProperty("other_eye_insertions", 1);
                    case "fixture" -> {
                        portal.addProperty("generated_stronghold", false);
                        portal.addProperty("portal_origin", "calibration_fixture");
                    }
                    case "insertion-count" -> portal.addProperty("companion_eye_insertions", 1);
                }
            }
            run.observe(START + 1, state);
            assertFalse(run.finished(), mode);
        }
    }

    @Test
    void directDimensionChangeAndLeavingTheEndDoNotCountAsNativeEntry() {
        LiveEvalRun direct = startedEndEntry();
        JsonObject changed = endEntrySnapshot(129);
        changed.addProperty("dimension", "minecraft:the_end");
        changed.getAsJsonObject("world").addProperty("native_end_entry", true);
        direct.event("dimension_change", json("{\"to_dimension\":\"minecraft:the_end\"}"), START + 1);
        direct.observe(START + 2, changed);
        assertFalse(direct.finished());
        LiveEvalRun left = startedEndEntry();
        JsonObject state = nativeEndEntrySnapshot(128, 19);
        left.observe(START + 1, state);
        state.addProperty("dimension", "minecraft:overworld");
        state.addProperty("game_tick", 129);
        endEntryChain(state).addProperty("last_verified_game_tick", 129);
        state.getAsJsonObject("world").addProperty("end_alive_held_ticks", 0);
        left.observe(START + 2, state);
        assertFalse(left.finished());
        assertEquals(0, left.report().get("success_held_ticks").getAsLong());
    }

    @Test
    void endAliveHoldAndFinalBodyThresholdsHaveSeparateMeanings() {
        LiveEvalRun run = startedEndEntry();
        run.observe(START + 1, nativeEndEntrySnapshot(128, 19));
        assertFalse(run.finished());
        JsonObject state = nativeEndEntrySnapshot(129, 20);
        state.addProperty("health", 15);
        run.observe(START + 2, state);
        assertFalse(run.finished());
        state.addProperty("health", 16);
        state.addProperty("food", 15);
        run.observe(START + 3, state);
        assertFalse(run.finished());
        state.addProperty("food", 16);
        run.observe(START + 4, state);
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(20, run.report().get("success_held_ticks").getAsLong(), "health recovery does not restart the already proven alive hold");
    }

    @Test
    void serverObservationGapsCannotBeFilledByLaterCompletePortalFlags() {
        for (boolean complete : new boolean[]{true, false}) {
            LiveEvalRun run = startedEndEntry();
            JsonObject state = nativeEndEntrySnapshot(129, 20);
            state.getAsJsonObject("world").addProperty("observation_complete", complete);
            endEntryChain(state).addProperty("observation_gaps", 1);
            run.observe(START + 1, state);
            outcome(run, "infra_error", "observation_incomplete");
        }
        LiveEvalRun stale = startedEndEntry();
        JsonObject state = nativeEndEntrySnapshot(129, 20);
        endEntryChain(state).addProperty("last_verified_game_tick", 128);
        stale.observe(START + 1, state);
        assertFalse(stale.finished(), "stale server evidence cannot supply current alive time");
        LiveEvalRun incomplete = startedEndEntry();
        state = nativeEndEntrySnapshot(129, 20);
        endEntryChain(state).addProperty("critical_evidence_complete", false);
        incomplete.observe(START + 1, state);
        assertFalse(incomplete.finished());
    }

    @Test
    void missingSubjectIsAnObservationFailureWhileFirstDeathStillWins() {
        LiveEvalRun missing = startedEndEntry();
        JsonObject state = endEntrySnapshot(100);
        state.addProperty("subject_present", false);
        state.addProperty("alive", false);
        state.remove("world");
        missing.observe(START + 1, state);
        outcome(missing, "infra_error", "observation_unavailable");
        LiveEvalRun replaced = startedEndEntry();
        state = nativeEndEntrySnapshot(129, 20);
        state.addProperty("companion_uuid", "replacement-subject");
        replaced.observe(START + 1, state);
        outcome(replaced, "infra_error", "companion_identity_changed");
        for (boolean alive : new boolean[]{false, true}) {
            LiveEvalRun dead = startedEndEntry();
            dead.observe(START + 1, json("{\"alive\":" + alive + ",\"deaths\":1}"));
            outcome(dead, "failed", "companion_died");
        }
    }

    @Test
    void missingOrMalformedEndEntryEvidenceIsAnInfrastructureFailure() {
        for (String field : new String[]{"critical_evidence_complete", "entry", "subject_uuid", "last_verified_game_tick", "first_death_observed"}) {
            LiveEvalRun run = startedEndEntry();
            JsonObject state = nativeEndEntrySnapshot(129, 20);
            endEntryChain(state).remove(field);
            run.observe(START + 1, state);
            assertEquals("infra_error", run.report().get("status").getAsString(), field);
            assertTrue(run.report().get("reason").getAsString().startsWith("invalid_snapshot:"));
        }
    }

    private static JsonObject dragonCombatSpec() {
        JsonObject spec = spec();
        spec.addProperty("evaluation_version", "dragon-combat-v1");
        spec.addProperty("world_observer", "dragon-combat-v1");
        spec.addProperty("case_id", "live.dragon-combat");
        spec.addProperty("success_hold_ticks", 200);
        spec.add("setup", json("{\"dimension\":\"minecraft:the_end\",\"native_dragon_battle\":true,\"equipment\":{\"head\":\"minecraft:iron_helmet\"}}"));
        spec.add("success", json("{\"world\":{\"native_dragon_kill\":true}}"));
        spec.add("milestones", JsonParser.parseString("""
                [{"id":"dragon-damaged","condition":{"world":{"dragon_damaged":true}}},
                 {"id":"dragon-death-started","condition":{"world":{"dragon_death_started":true}}},
                 {"id":"dragon-defeated","condition":{"world":{"native_dragon_kill":true}}}]
                """));
        return spec;
    }

    private static JsonObject dragonIdentity(JsonObject value) {
        value.addProperty("subject_uuid", SUBJECT);
        value.addProperty("target_uuid", "d58f701d-4244-4a8e-aa78-0db5f927cab0");
        value.addProperty("fight_id", "minecraft:the_end:0:0:0:d58f701d-4244-4a8e-aa78-0db5f927cab0");
        value.addProperty("dimension", "minecraft:the_end");
        value.add("origin", JsonParser.parseString("[0,0,0]"));
        return value;
    }

    private static JsonObject dragonCombatSnapshot(long tick) {
        JsonObject state = snapshot();
        state.addProperty("companion_uuid", SUBJECT);
        state.addProperty("subject_present", true);
        state.addProperty("dimension", "minecraft:the_end");
        state.addProperty("game_tick", tick);
        JsonObject initial = dragonIdentity(json("""
                {"game_tick":90,"evidence_source":"server_native_dragon_fight_initial_state",
                 "fight_bound":true,"needs_state_scanning":false,"dragon_killed":false,"previously_killed":false,
                 "is_respawning":false,"target_present":true,"target_health":200,"target_max_health":200,
                 "target_no_ai":false,"target_invulnerable":false,"crystals_alive":10,"dragon_kills":0,"calibration_fixture":false,
                 "phase":"HoldingPattern (#0)","death_animation_ticks":0}
                """));
        JsonArray crystals = new JsonArray();
        for (int i = 0; i < 10; i++) {
            JsonObject crystal = json("{\"invulnerable\":false,\"alive\":true}");
            crystal.addProperty("uuid", "crystal-" + i);
            crystal.add("position", JsonParser.parseString("[" + (i * 10) + ",80,40]"));
            crystals.add(crystal);
        }
        initial.add("crystals", crystals);
        JsonObject current = initial.deepCopy();
        current.addProperty("game_tick", tick);
        current.addProperty("phase", "HOLDING_PATTERN");
        current.addProperty("death_animation_ticks", 0);
        JsonObject chain = dragonIdentity(json("""
                {"first_damage":null,"first_companion_damage":null,"lethal":null,"kill_credit":null,"kill_statistic":null,
                 "fight_end":null,"first_death":null,"first_death_observed":false,"observation_gaps":0,"critical_evidence_complete":true,
                 "attribution_ambiguous":false,"total_actual_damage":0,"companion_actual_damage":0,
                 "minimum_health":200,"current_health":200,"calibration_fixture":false,
                 "post_kill_alive_ticks":0,"post_kill_hold_start_game_tick":null,"post_kill_last_verified_game_tick":null}
                """));
        chain.addProperty("last_verified_game_tick", tick);
        chain.add("initial_battle", initial);
        chain.add("current_battle", current);
        JsonObject world = json("""
                {"dragon_combat_ready":true,"observation_complete":true,"dragon_damaged":false,
                 "dragon_death_started":false,"native_dragon_kill":false,"dragon_post_kill_alive_ticks":0,"evidence":{}}
                """);
        world.addProperty("subject_uuid", SUBJECT);
        world.getAsJsonObject("evidence").add("dragon_combat", chain);
        state.add("world", world);
        return state;
    }

    private static JsonObject dragonCombatChain(JsonObject state) {
        return state.getAsJsonObject("world").getAsJsonObject("evidence").getAsJsonObject("dragon_combat");
    }

    private static JsonObject dragonDeathStartedSnapshot(long tick) {
        JsonObject state = dragonCombatSnapshot(tick);
        JsonObject chain = dragonCombatChain(state);
        JsonObject damage = dragonIdentity(json("""
                {"game_tick":100,"evidence_source":"native_ender_dragon_really_hurt","event_id":1,
                 "health_before":200,"health_after":170,"actual_health_loss":30,
                 "attribution_origin":"native_damage_source","provenance_actor_uuid":null,
                 "direct_entity_uuid":null,"damage_type_message_id":"player","attribution_reliable":true}
                """));
        damage.addProperty("actor_uuid", SUBJECT);
        damage.addProperty("source_actor_uuid", SUBJECT);
        chain.add("first_damage", damage.deepCopy());
        chain.add("first_companion_damage", damage);
        JsonObject lethal = dragonIdentity(json("""
                {"game_tick":105,"evidence_source":"native_living_entity_die","health":0,"damage_event_id":2,
                 "health_before":170,"health_after":0,"actual_health_loss":170,"damage_type_message_id":"player",
                 "direct_entity_uuid":null,"attribution_origin":"native_damage_source","provenance_actor_uuid":null}
                """));
        lethal.addProperty("source_actor_uuid", SUBJECT);
        lethal.addProperty("credited_actor_uuid", SUBJECT);
        JsonObject fatal = damage.deepCopy();
        fatal.addProperty("event_id", 2);
        fatal.addProperty("game_tick", 105);
        fatal.addProperty("health_before", 170);
        fatal.addProperty("health_after", 0);
        fatal.addProperty("actual_health_loss", 170);
        lethal.add("fatal_damage", fatal);
        chain.add("lethal", lethal);
        chain.addProperty("total_actual_damage", 200);
        chain.addProperty("companion_actual_damage", 200);
        chain.addProperty("minimum_health", 0);
        chain.addProperty("current_health", 1);
        JsonObject current = chain.getAsJsonObject("current_battle");
        current.addProperty("target_health", 1);
        current.addProperty("phase", "DYING");
        current.addProperty("death_animation_ticks", tick - 105 + 1);
        state.getAsJsonObject("world").addProperty("dragon_damaged", true);
        state.getAsJsonObject("world").addProperty("dragon_death_started", true);
        return state;
    }

    private static JsonObject dragonCompletedSnapshot(long tick) {
        JsonObject state = dragonDeathStartedSnapshot(tick);
        JsonObject chain = dragonCombatChain(state);
        JsonObject credit = dragonIdentity(json("""
                {"game_tick":105,"evidence_source":"native_server_player_award_kill_score","damage_type_message_id":"player"}
                """));
        credit.addProperty("actor_uuid", SUBJECT);
        credit.addProperty("source_actor_uuid", SUBJECT);
        credit.add("direct_entity_uuid", com.google.gson.JsonNull.INSTANCE);
        chain.add("kill_credit", credit);
        JsonObject statistic = dragonIdentity(json("""
                {"game_tick":105,"evidence_source":"native_player_killed_entity_statistic","before":0,"after":1}
                """));
        statistic.addProperty("actor_uuid", SUBJECT);
        chain.add("kill_statistic", statistic);
        JsonObject ended = dragonIdentity(json("""
                {"game_tick":304,"evidence_source":"native_end_dragon_fight_set_dragon_killed",
                 "dragon_killed":true,"previously_killed":true,"death_animation_ticks":200}
                """));
        chain.add("fight_end", ended);
        chain.add("current_health", com.google.gson.JsonNull.INSTANCE);
        JsonObject current = chain.getAsJsonObject("current_battle");
        current.addProperty("target_present", false);
        current.add("target_health", com.google.gson.JsonNull.INSTANCE);
        current.addProperty("dragon_killed", true);
        current.addProperty("previously_killed", true);
        current.addProperty("death_animation_ticks", 200);
        current.addProperty("dragon_kills", 1);
        state.addProperty("dragon_kills", 1);
        state.getAsJsonObject("world").addProperty("native_dragon_kill", true);
        state.getAsJsonObject("world").addProperty("dragon_post_kill_alive_ticks", tick - 304);
        chain.addProperty("post_kill_alive_ticks", tick - 304);
        if (tick > 304) {
            chain.addProperty("post_kill_hold_start_game_tick", 305);
            chain.addProperty("post_kill_last_verified_game_tick", tick);
        }
        return state;
    }

    private static LiveEvalRun startedDragonCombat() {
        LiveEvalRun run = new LiveEvalRun(dragonCombatSpec());
        run.start(START, dragonCombatSnapshot(90));
        assertFalse(run.finished(), run.report().toString());
        return run;
    }

    @Test
    void dragonCombatProtocolRequiresItsObserverAndNativeKillCriterion() {
        assertEquals(dragonCombatSpec(), new LiveEvalRun(dragonCombatSpec()).report().getAsJsonObject("spec"));
        for (String observer : new String[]{"end-entry-v1", "end-resources-v1", "expedition-v1"}) {
            JsonObject spec = dragonCombatSpec();
            spec.addProperty("world_observer", observer);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec));
        }
        for (String criterion : new String[]{"{\"dragon_kills\":1}", "{\"world\":{\"native_dragon_kill\":false}}"}) {
            JsonObject spec = dragonCombatSpec();
            spec.add("success", json(criterion));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec));
        }
        for (int ticks : new int[]{0, 20, 199, 201}) {
            JsonObject spec = dragonCombatSpec();
            if (ticks == 0) spec.remove("success_hold_ticks");
            else spec.addProperty("success_hold_ticks", ticks);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec));
        }
    }

    @Test
    void dragonSuccessRequiresNativeDamageDeathCreditStatisticAndAnimationEnd() {
        LiveEvalRun run = startedDragonCombat();
        JsonObject dying = dragonDeathStartedSnapshot(303);
        run.observe(START + 1, dying);
        assertFalse(run.finished(), "native lethal is progress while the dragon death animation is running");
        assertEquals(2, run.report().getAsJsonArray("milestones").size());
        run.observe(START + 2, dragonCompletedSnapshot(304));
        assertFalse(run.finished(), "the battle-end tick must finish before success can commit");
        run.observe(START + 3, dragonCompletedSnapshot(305));
        assertFalse(run.finished(), "native kill evidence is complete, but the post-kill survival requirement is separate");
        assertEquals(1, run.report().get("success_held_ticks").getAsLong());
        run.observe(START + 4, dragonCompletedSnapshot(503));
        assertFalse(run.finished());
        run.observe(START + 5, dragonCompletedSnapshot(504));
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(3, run.report().getAsJsonArray("milestones").size());
        assertEquals(200, run.report().get("success_held_ticks").getAsLong());
    }

    @Test
    void dragonKillClaimsStatisticsAndDeathFlagsCannotSubstituteForNativeEvidence() {
        for (String mode : new String[]{"claim", "statistic", "phase", "flags"}) {
            LiveEvalRun run = startedDragonCombat();
            JsonObject state = dragonCombatSnapshot(100);
            run.event("assistant", json("{\"text\":\"末影龙已经死了\"}"), START + 1);
            if ("statistic".equals(mode)) {
                state.addProperty("dragon_kills", 1);
                dragonCombatChain(state).getAsJsonObject("current_battle").addProperty("dragon_kills", 1);
            } else if ("phase".equals(mode)) {
                dragonCombatChain(state).getAsJsonObject("current_battle").addProperty("phase", "DYING");
            } else if ("flags".equals(mode)) {
                for (String key : new String[]{"dragon_damaged", "dragon_death_started", "native_dragon_kill"}) state.getAsJsonObject("world").addProperty(key, true);
            }
            run.observe(START + 2, state);
            assertNotEquals("success", run.report().get("status").getAsString(), mode);
            assertEquals(0, run.report().getAsJsonArray("milestones").size(), mode);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(dragonCombatSpec()).finish("success", "claimed", START));
        }
    }

    @Test
    void dragonInitialStateMustBeThisFreshFullNativeFightWithAllCrystals() {
        java.util.List<java.util.function.Consumer<JsonObject>> corruptions = java.util.List.of(
                state -> state.addProperty("dragon_kills", 1),
                state -> state.addProperty("deaths", 1),
                state -> state.getAsJsonObject("world").addProperty("dragon_combat_ready", false),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("target_health", 1),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("dragon_killed", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("previously_killed", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("fight_bound", false),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("needs_state_scanning", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("target_no_ai", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("target_invulnerable", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").addProperty("phase", "Dying (#9)"),
                state -> dragonCombatChain(state).addProperty("calibration_fixture", true),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").getAsJsonArray("crystals").get(0).getAsJsonObject().addProperty("alive", false),
                state -> dragonCombatChain(state).getAsJsonObject("initial_battle").getAsJsonArray("crystals").remove(0));
        for (var corruption : corruptions) {
            JsonObject initial = dragonCombatSnapshot(90);
            corruption.accept(initial);
            LiveEvalRun run = new LiveEvalRun(dragonCombatSpec());
            run.start(START, initial);
            assertEquals("infra_error", run.report().get("status").getAsString(), run.report().toString());
        }
    }

    @Test
    void dragonIdentitiesAndInitialBattleCannotBeReplacedByACompleteSelfConsistentChain() {
        for (String mode : new String[]{"subject", "target", "fight", "baseline"}) {
            LiveEvalRun run = startedDragonCombat();
            JsonObject state = dragonCompletedSnapshot(304);
            JsonObject chain = dragonCombatChain(state);
            if ("subject".equals(mode)) state.addProperty("companion_uuid", "replacement");
            else if ("baseline".equals(mode)) chain.getAsJsonObject("initial_battle").getAsJsonArray("crystals")
                    .get(0).getAsJsonObject().addProperty("uuid", "replacement-crystal");
            else {
                String field = "target".equals(mode) ? "target_uuid" : "fight_id";
                chain.addProperty(field, "replacement");
                for (String key : new String[]{"initial_battle", "current_battle", "first_damage", "first_companion_damage", "lethal", "kill_credit", "kill_statistic", "fight_end"}) {
                    chain.getAsJsonObject(key).addProperty(field, "replacement");
                }
            }
            run.observe(START + 1, state);
            assertEquals("infra_error", run.report().get("status").getAsString(), mode);
        }
    }

    @Test
    void targetMissingRemovedUnloadedOrChangingDimensionIsInfrastructureNotAnEntityDisappearanceKill() {
        for (String mode : new String[]{"missing-dragon", "missing-subject", "dimension", "gap", "stale", "critical"}) {
            LiveEvalRun run = startedDragonCombat();
            JsonObject state = dragonCombatSnapshot(100);
            JsonObject chain = dragonCombatChain(state);
            switch (mode) {
                case "missing-dragon" -> {
                    chain.getAsJsonObject("current_battle").addProperty("target_present", false);
                    chain.getAsJsonObject("current_battle").add("target_health", com.google.gson.JsonNull.INSTANCE);
                }
                case "missing-subject" -> {
                    state.addProperty("subject_present", false);
                    state.addProperty("alive", false);
                    state.remove("world");
                }
                case "dimension" -> state.addProperty("dimension", "minecraft:overworld");
                case "gap" -> chain.addProperty("observation_gaps", 1);
                case "stale" -> chain.addProperty("last_verified_game_tick", 99);
                case "critical" -> chain.addProperty("critical_evidence_complete", false);
            }
            run.observe(START + 1, state);
            assertEquals("infra_error", run.report().get("status").getAsString(), mode);
        }
    }

    @Test
    void firstCompanionDeathWinsOverSameTickNativeDragonSuccessAndAutomaticRecovery() {
        for (String mode : new String[]{"statistic", "latch", "missing-body", "after-kill", "receipt-only"}) {
            LiveEvalRun run = startedDragonCombat();
            JsonObject state = dragonCompletedSnapshot("after-kill".equals(mode) ? 503 : 304);
            if ("statistic".equals(mode)) state.addProperty("deaths", 1);
            else if ("receipt-only".equals(mode)) {
                JsonObject death = dragonIdentity(json("{\"game_tick\":304,\"evidence_source\":\"native_companion_death_before_recovery\",\"health_at_death\":0}"));
                dragonCombatChain(state).add("first_death", death);
            }
            else {
                dragonCombatChain(state).addProperty("first_death_observed", true);
                dragonCombatChain(state).addProperty("critical_evidence_complete", false);
                if ("missing-body".equals(mode)) {
                    state.addProperty("subject_present", false);
                    state.addProperty("alive", false);
                }
            }
            run.observe(START + 1, state);
            outcome(run, "failed", "companion_died");
        }
    }

    @Test
    void knownOtherKillerFailsButAmbiguousCrystalProximityAttributionIsInfrastructure() {
        LiveEvalRun other = startedDragonCombat();
        JsonObject state = dragonDeathStartedSnapshot(105);
        JsonObject lethal = dragonCombatChain(state).getAsJsonObject("lethal");
        lethal.addProperty("source_actor_uuid", "other-player");
        lethal.addProperty("credited_actor_uuid", "other-player");
        lethal.getAsJsonObject("fatal_damage").addProperty("source_actor_uuid", "other-player");
        lethal.getAsJsonObject("fatal_damage").addProperty("actor_uuid", "other-player");
        JsonObject otherCredit = dragonIdentity(json("""
                {"game_tick":105,"evidence_source":"native_server_player_award_kill_score","damage_type_message_id":"player",
                 "actor_uuid":"other-player","source_actor_uuid":"other-player","direct_entity_uuid":null}
                """));
        dragonCombatChain(state).add("kill_credit", otherCredit);
        other.observe(START + 1, state);
        outcome(other, "failed", "target_killed_by_other");
        for (String mode : new String[]{"nearest", "ambiguous", "no-credit", "unknown-source"}) {
            LiveEvalRun run = startedDragonCombat();
            state = dragonCompletedSnapshot(304);
            lethal = dragonCombatChain(state).getAsJsonObject("lethal");
            switch (mode) {
                case "nearest" -> {
                    lethal.addProperty("attribution_origin", "crystal_nearest_player_fallback");
                    lethal.getAsJsonObject("fatal_damage").addProperty("attribution_origin", "crystal_nearest_player_fallback");
                }
                case "ambiguous" -> dragonCombatChain(state).addProperty("attribution_ambiguous", true);
                case "no-credit" -> lethal.add("credited_actor_uuid", com.google.gson.JsonNull.INSTANCE);
                case "unknown-source" -> {
                    lethal.add("source_actor_uuid", com.google.gson.JsonNull.INSTANCE);
                    lethal.getAsJsonObject("fatal_damage").add("source_actor_uuid", com.google.gson.JsonNull.INSTANCE);
                    lethal.getAsJsonObject("fatal_damage").add("actor_uuid", com.google.gson.JsonNull.INSTANCE);
                }
            }
            run.observe(START + 1, state);
            outcome(run, "infra_error", "attribution_unavailable");
        }
    }

    @Test
    void malformedOrIncompleteNativeDragonTerminalChainCannotPass() {
        java.util.List<java.util.function.Consumer<JsonObject>> corruptions = java.util.List.of(
                state -> dragonCombatChain(state).add("kill_credit", com.google.gson.JsonNull.INSTANCE),
                state -> dragonCombatChain(state).add("kill_statistic", com.google.gson.JsonNull.INSTANCE),
                state -> dragonCombatChain(state).getAsJsonObject("lethal").addProperty("evidence_source", "direct_set_health_then_die"),
                state -> dragonCombatChain(state).getAsJsonObject("lethal").addProperty("actual_health_loss", 0),
                state -> dragonCombatChain(state).getAsJsonObject("kill_credit").addProperty("actor_uuid", "other-player"),
                state -> dragonCombatChain(state).getAsJsonObject("kill_statistic").addProperty("after", 2),
                state -> dragonCombatChain(state).getAsJsonObject("fight_end").addProperty("game_tick", 106),
                state -> dragonCombatChain(state).getAsJsonObject("fight_end").addProperty("death_animation_ticks", 199),
                state -> dragonCombatChain(state).getAsJsonObject("fight_end").addProperty("death_animation_ticks", 201),
                state -> dragonCombatChain(state).getAsJsonObject("fight_end").addProperty("target_uuid", "other-dragon"),
                state -> {
                    dragonCombatChain(state).getAsJsonObject("lethal").addProperty("damage_event_id", 1);
                    dragonCombatChain(state).getAsJsonObject("lethal").getAsJsonObject("fatal_damage").addProperty("event_id", 1);
                },
                state -> dragonCombatChain(state).getAsJsonObject("lethal").remove("damage_event_id"));
        for (var corruption : corruptions) {
            LiveEvalRun run = startedDragonCombat();
            JsonObject state = dragonCompletedSnapshot(304);
            corruption.accept(state);
            run.observe(START + 1, state);
            assertEquals("infra_error", run.report().get("status").getAsString(), run.report().toString());
        }
    }

    @Test
    void repeatedDragonReceiptsKeepSingleMilestonesAndCannotRewriteTheFirstDamage() {
        LiveEvalRun run = startedDragonCombat();
        JsonObject state = dragonDeathStartedSnapshot(110);
        run.observe(START + 1, state);
        run.observe(START + 2, state.deepCopy());
        assertEquals(2, run.report().getAsJsonArray("milestones").size());
        assertEquals(200, dragonCombatChain(run.report().getAsJsonObject("final_snapshot")).get("companion_actual_damage").getAsDouble());
        JsonObject changed = dragonDeathStartedSnapshot(111);
        dragonCombatChain(changed).getAsJsonObject("first_companion_damage").addProperty("event_id", 3);
        run.observe(START + 3, changed);
        outcome(run, "infra_error", "dragon_evidence_inconsistent");
    }

    @Test
    void postKillSurvivalUsesOnlySealedServerTicksAndCanContinueAcrossNativeDimensions() {
        LiveEvalRun run = startedDragonCombat();
        JsonObject held = dragonCompletedSnapshot(404);
        run.observe(START + 1, held);
        run.observe(START + 2, held.deepCopy());
        assertEquals(100, run.report().get("success_held_ticks").getAsLong(), "repeated reads do not contribute time");
        assertFalse(run.finished());
        JsonObject returned = dragonCompletedSnapshot(504);
        returned.addProperty("dimension", "minecraft:overworld");
        run.observe(START + 3, returned);
        outcome(run, "success", "authoritative_success_criterion_met");
        for (String mode : new String[]{"inflated", "stale-hold", "different-start", "missing-world-counter"}) {
            LiveEvalRun invalid = startedDragonCombat();
            JsonObject state = dragonCompletedSnapshot(504);
            switch (mode) {
                case "inflated" -> state.getAsJsonObject("world").addProperty("dragon_post_kill_alive_ticks", 201);
                case "stale-hold" -> dragonCombatChain(state).addProperty("post_kill_last_verified_game_tick", 503);
                case "different-start" -> dragonCombatChain(state).addProperty("post_kill_hold_start_game_tick", 304);
                case "missing-world-counter" -> state.getAsJsonObject("world").remove("dragon_post_kill_alive_ticks");
            }
            invalid.observe(START + 1, state);
            assertEquals("infra_error", invalid.report().get("status").getAsString(), mode);
        }
    }

    @Test
    void endResourcesRequiresCurrentResourcesAndSuppliesEvenAfterEveryMilestoneWasReached() {
        JsonObject spec = spec();
        spec.addProperty("evaluation_version", "end-resources-v1");
        spec.addProperty("world_observer", "end-resources-v1");
        spec.addProperty("success_hold_ticks", 200);
        spec.add("success", json("""
                {"world":{"eye_equivalents":16,"armor_ready":true,"safe_state":true,"food_nutrition":80},
                 "items":{"minecraft:water_bucket":1,"minecraft:torch":32},"min_health":16,"min_food":16}
                """));
        spec.add("milestones", JsonParser.parseString("""
                [{"id":"resources","condition":{"world":{"eye_equivalents":16}}},
                 {"id":"ready","condition":{"world":{"eye_equivalents":16,"armor_ready":true,"safe_state":true}}}]
                """));
        JsonObject state = snapshot();
        state.addProperty("game_tick", 0);
        state.add("world", json("{\"eye_equivalents\":0,\"armor_ready\":true,\"safe_state\":true,\"food_nutrition\":128,\"loadout_held_ticks\":0}"));
        state.add("inventory", json("{\"minecraft:water_bucket\":1,\"minecraft:torch\":32}"));
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, state);
        assertEquals(0, run.report().getAsJsonArray("milestones").size(), "prepared starter gear is not stage-3 resources");
        state.getAsJsonObject("world").addProperty("eye_equivalents", 16);
        for (int tick = 20; tick <= 200; tick += 20) {
            state.addProperty("game_tick", tick);
            state.getAsJsonObject("world").addProperty("loadout_held_ticks", tick - 20);
            run.observe(START + tick, state);
        }
        assertEquals(2, run.report().getAsJsonArray("milestones").size());
        assertEquals(180, run.report().get("success_held_ticks").getAsInt());
        assertFalse(run.finished(), "all historical milestones cannot replace the hold");
        state.addProperty("game_tick", 220);
        state.getAsJsonObject("world").addProperty("eye_equivalents", 15);
        state.getAsJsonObject("world").addProperty("loadout_held_ticks", 0);
        run.observe(START + 220, state);
        assertEquals(0, run.report().get("success_held_ticks").getAsInt());
        state.getAsJsonObject("world").addProperty("eye_equivalents", 16);
        state.addProperty("game_tick", 240);
        state.getAsJsonObject("inventory").remove("minecraft:water_bucket");
        run.observe(START + 240, state);
        assertEquals(0, run.report().get("success_held_ticks").getAsInt());
        state.getAsJsonObject("inventory").addProperty("minecraft:water_bucket", 1);
        state.getAsJsonObject("world").addProperty("safe_state", false);
        state.addProperty("game_tick", 260);
        run.observe(START + 260, state);
        assertFalse(run.finished(), "restored resources cannot hide danger");
        state.getAsJsonObject("world").addProperty("safe_state", true);
        for (int tick = 280; tick <= 480; tick += 20) {
            state.addProperty("game_tick", tick);
            state.getAsJsonObject("world").addProperty("loadout_held_ticks", tick - 280);
            run.observe(START + tick, state);
            if (tick < 480) assertFalse(run.finished());
        }
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(200, run.report().get("success_held_ticks").getAsInt());
    }

    @Test
    void endResourceHoldRejectsBetweenSnapshotInterruptionsAndMissingTickEvidence() {
        JsonObject spec = survivalSpec();
        spec.addProperty("evaluation_version", "end-resources-v1");
        spec.addProperty("world_observer", "end-resources-v1");
        spec.add("success", json("{\"world\":{\"eye_equivalents\":16}}"));
        JsonObject state = snapshot();
        state.addProperty("game_tick", 100);
        state.add("world", json("{\"eye_equivalents\":16,\"loadout_held_ticks\":0}"));
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, state);
        state.addProperty("game_tick", 120);
        state.getAsJsonObject("world").addProperty("loadout_held_ticks", 20);
        run.observe(START + 1, state);
        state.addProperty("game_tick", 140);
        state.getAsJsonObject("world").addProperty("loadout_held_ticks", 1);
        run.observe(START + 2, state);
        assertFalse(run.finished(), "a lost-and-restored shield between snapshots must invalidate the apparent forty-tick hold");
        assertEquals(1, run.report().get("success_held_ticks").getAsInt());
        for (int tick : new int[]{160, 180}) {
            state.addProperty("game_tick", tick);
            state.getAsJsonObject("world").addProperty("loadout_held_ticks", tick - 139);
            run.observe(START + tick, state);
        }
        outcome(run, "success", "authoritative_success_criterion_met");
        for (String world : new String[]{"{\"eye_equivalents\":16}",
                "{\"eye_equivalents\":16,\"loadout_held_ticks\":-1}",
                "{\"eye_equivalents\":16,\"loadout_held_ticks\":1.5}",
                "{\"eye_equivalents\":16,\"loadout_held_ticks\":true}"}) {
            state.add("world", json(world));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec).start(START, state), world);
        }
    }

    @Test
    void equipmentSetupIsExplicitAndCannotReinterpretOlderProtocols() {
        JsonObject spec = spec();
        spec.addProperty("evaluation_version", "end-resources-v1");
        spec.add("setup", json("{\"equipment\":{\"head\":\"minecraft:iron_helmet\",\"offhand\":\"minecraft:shield\"}}"));
        assertEquals(spec, new LiveEvalRun(spec).report().getAsJsonObject("spec"));
        for (String setup : new String[]{"{\"equipment\":[]}", "{\"equipment\":null}",
                "{\"equipment\":{\"mainhand\":\"minecraft:iron_pickaxe\"}}", "{\"equipment\":{\"head\":1}}",
                "{\"equipment\":{\"head\":\"iron_helmet\"}}", "{\"equipment\":{\"head\":\"\"}}"}) {
            spec.add("setup", json(setup));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), setup);
        }
        for (String version : new String[]{"live-v1", "survival-v1", "survival-v2", "expedition-v1"}) {
            spec.addProperty("evaluation_version", version);
            spec.add("setup", json("{\"equipment\":{}}"));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), version);
            spec.addProperty("world_observer", "end-resources-v1");
            spec.add("setup", json("{}"));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), version);
        }
        spec.addProperty("evaluation_version", "end-resources-v1");
        for (String observer : new String[]{"expedition-v1", "free-survival-v2", "sustainable-survival-v1"}) {
            spec.addProperty("world_observer", observer);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), observer);
        }
    }

    @Test
    void foodInitializationIsAnExplicitV2FixtureWithoutRewritingDefaults() {
        for (String setup : new String[]{"{}", "{\"food_level\":14,\"saturation\":0}",
                "{\"food_level\":0,\"saturation\":0}", "{\"food_level\":20,\"saturation\":20}",
                "{\"food_level\":4}", "{\"saturation\":5.5}"}) {
            JsonObject spec = spec();
            spec.addProperty("evaluation_version", "survival-v2");
            spec.add("setup", json(setup));
            LiveEvalRun run = new LiveEvalRun(spec);
            assertEquals(spec, run.report().getAsJsonObject("spec"), setup);
        }
    }

    @Test
    void foodInitializationRejectsMalformedOrOutOfRangeValues() {
        for (String setup : new String[]{"{\"food_level\":-1}", "{\"food_level\":21}",
                "{\"food_level\":14.5}", "{\"food_level\":true}", "{\"food_level\":false}",
                "{\"food_level\":\"14\"}", "{\"food_level\":[]}", "{\"food_level\":{}}", "{\"food_level\":null}",
                "{\"saturation\":-1}", "{\"saturation\":21}", "{\"food_level\":14,\"saturation\":15}",
                "{\"food_level\":0,\"saturation\":0.1}", "{\"saturation\":true}", "{\"saturation\":false}",
                "{\"saturation\":\"0\"}", "{\"saturation\":[]}", "{\"saturation\":{}}", "{\"saturation\":null}"}) {
            JsonObject spec = spec();
            spec.addProperty("evaluation_version", "survival-v2");
            spec.add("setup", json(setup));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), setup);
        }
        for (String field : new String[]{"food_level", "saturation"}) {
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                JsonObject spec = spec();
                spec.addProperty("evaluation_version", "survival-v2");
                JsonObject setup = json("{\"food_level\":14,\"saturation\":0}");
                setup.addProperty(field, value);
                spec.add("setup", setup);
                assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), field + ":" + value);
            }
        }
    }

    @Test
    void foodInitializationCannotBeUsedOutsideSurvivalV2() {
        for (String version : new String[]{"live-v1", "survival-v1", "expedition-v1"}) {
            for (String setup : new String[]{"{\"food_level\":14}", "{\"saturation\":0}",
                    "{\"food_level\":14,\"saturation\":0}"}) {
                JsonObject spec = spec();
                spec.addProperty("evaluation_version", version);
                spec.add("setup", json(setup));
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> new LiveEvalRun(spec), version + ":" + setup);
                assertTrue(error.getMessage().contains("survival-v2"));
            }
        }
    }

    @Test
    void survivalFactsMustCoexistEvenWhenSeparateMilestonesHaveBeenReached() {
        JsonObject spec = survivalSpec();
        spec.remove("success_hold_ticks");
        spec.add("milestones", JsonParser.parseString("""
                [{"id":"harvest","condition":{"world":{"harvested_food":4}}},
                 {"id":"food-source","condition":{"world":{"renewable_food":true}}}]
                """));
        LiveEvalRun run = new LiveEvalRun(spec);
        run.start(START, survivalSnapshot(100, false, 0));
        run.observe(START + 1, survivalSnapshot(104, false, 4));
        run.observe(START + 2, survivalSnapshot(108, true, 0));
        run.observe(START + 3, survivalSnapshot(112, true, 2));
        run.observe(START + 4, survivalSnapshot(116, true, 2));
        assertEquals(2, run.report().getAsJsonArray("milestones").size());
        assertFalse(run.finished(), "neither past milestones nor repeated partial harvest counts combine into success");
        run.observe(START + 5, survivalSnapshot(120, true, 4));
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals("survival-v1", run.report().get("evaluation_version").getAsString());
    }

    @Test
    void missingOrMistypedSurvivalFactsAreObservationErrorsRatherThanFailedCapabilities() {
        for (String facts : new String[]{"{}", "{\"renewable_food\":true}",
                "{\"renewable_food\":\"true\",\"harvested_food\":4}",
                "{\"renewable_food\":true,\"harvested_food\":true}",
                "{\"renewable_food\":true,\"harvested_food\":-1}"}) {
            LiveEvalRun run = new LiveEvalRun(survivalSpec());
            run.start(START, survivalSnapshot(100, false, 0));
            JsonObject broken = survivalSnapshot(104, true, 4);
            broken.add("world", json(facts));
            run.observe(START + 1, broken);
            assertEquals("infra_error", run.report().get("status").getAsString(), facts);
            assertTrue(run.report().get("reason").getAsString().startsWith("invalid_snapshot:"));
        }
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, false, 0));
        JsonObject broken = survivalSnapshot(104, true, 4);
        broken.remove("game_tick");
        run.observe(START + 1, broken);
        assertEquals("infra_error", run.report().get("status").getAsString());
    }

    @Test
    void pausedWorldDoesNotContributeToTheSurvivalHold() {
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, true, 4));
        run.observe(START + 1_000_000_000L, survivalSnapshot(100, true, 4));
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        assertFalse(run.finished());
        run.observe(START + 2_000_000_000L, survivalSnapshot(120, true, 4));
        assertEquals(20, run.report().get("success_held_ticks").getAsLong());
        assertFalse(run.finished());
        run.observe(START + 3_000_000_000L, survivalSnapshot(140, true, 4));
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(40, run.report().get("success_held_ticks").getAsLong());
    }

    @Test
    void unobservedGameTimeCannotBeCountedAsContinuousSurvival() {
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, true, 4));
        run.observe(START + 1, survivalSnapshot(120, true, 4));
        run.observe(START + 2, survivalSnapshot(200, true, 4));
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        assertFalse(run.finished(), "a gap in observations cannot prove the intervening world stayed safe");
        run.observe(START + 3, survivalSnapshot(220, true, 4));
        assertFalse(run.finished());
        run.observe(START + 4, survivalSnapshot(240, true, 4));
        outcome(run, "success", "authoritative_success_criterion_met");
    }

    @Test
    void losingARequiredConditionRestartsTheEntireSurvivalHold() {
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, true, 4));
        run.observe(START + 1, survivalSnapshot(120, true, 4));
        JsonObject hungry = survivalSnapshot(130, true, 4);
        hungry.addProperty("food", 11);
        run.observe(START + 2, hungry);
        assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        run.observe(START + 3, survivalSnapshot(140, true, 4));
        run.observe(START + 4, survivalSnapshot(160, true, 4));
        assertFalse(run.finished(), "healthy intervals on either side of a hunger failure are not added together");
        run.observe(START + 5, survivalSnapshot(180, true, 4));
        outcome(run, "success", "authoritative_success_criterion_met");
    }

    @Test
    void deathAtTheHoldBoundaryWinsEvenIfTheBodyHasDisappearedOrRespawned() {
        for (boolean alive : new boolean[]{false, true}) {
            LiveEvalRun run = new LiveEvalRun(survivalSpec());
            run.start(START, survivalSnapshot(100, true, 4));
            run.observe(START + 1, survivalSnapshot(120, true, 4));
            run.observe(START + 2, survivalSnapshot(139, true, 4));
            JsonObject dead = new JsonObject();
            dead.addProperty("alive", alive);
            dead.addProperty("deaths", 1);
            dead.addProperty("game_tick", 140);
            run.observe(START + 3, dead);
            outcome(run, "failed", "companion_died");
            assertEquals(0, run.report().get("success_held_ticks").getAsLong());
        }
    }

    @Test
    void explicitFinishCannotReplaceObservedSurvivalTime() {
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, true, 4));
        assertThrows(IllegalArgumentException.class,
                () -> run.finish("success", "enough wall time elapsed", START + 3_000_000_000L));
        assertFalse(run.finished());
        run.observe(START + 3_100_000_000L, survivalSnapshot(120, true, 4));
        assertThrows(IllegalArgumentException.class,
                () -> run.finish("success", "assistant claimed stable", START + 3_200_000_000L));
        run.observe(START + 3_300_000_000L, survivalSnapshot(140, true, 4));
        outcome(run, "success", "authoritative_success_criterion_met");
    }

    @Test
    void rewoundGameClockIsAnInfrastructureFailure() {
        LiveEvalRun run = new LiveEvalRun(survivalSpec());
        run.start(START, survivalSnapshot(100, false, 0));
        run.observe(START + 1, survivalSnapshot(110, true, 4));
        run.observe(START + 2, survivalSnapshot(109, true, 4));
        outcome(run, "infra_error", "game_tick_moved_backwards");
    }

    @Test
    void originalProtocolStillScoresWithoutWorldFactsOrGameTicks() {
        LiveEvalRun run = started(spec());
        run.observe(START + 1, completed());
        outcome(run, "success", "authoritative_success_criterion_met");
        JsonObject report = run.report();
        assertEquals("live-v1", report.get("evaluation_version").getAsString());
        assertFalse(report.has("success_held_ticks"));
        assertFalse(report.getAsJsonObject("final_snapshot").has("world"));
        assertFalse(report.getAsJsonObject("final_snapshot").has("game_tick"));
        assertEquals(spec(), report.getAsJsonObject("spec"));
    }

    @Test
    void survivalCriteriaCannotBeEmptyNegativeFalseOrNested() {
        for (String facts : new String[]{"{}", "{\"renewable_food\":false}", "{\"harvested_food\":-1}",
                "{\"harvested_food\":\"4\"}", "{\"renewable_food\":{\"value\":true}}",
                "{\" \":true}"}) {
            JsonObject spec = survivalSpec();
            spec.getAsJsonObject("success").add("world", json(facts));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), facts);
        }
        for (String ticks : new String[]{"0", "-1", "1.5", "true", "\"40\""}) {
            JsonObject spec = survivalSpec();
            spec.add("success_hold_ticks", JsonParser.parseString(ticks));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), ticks);
        }
    }

    @Test
    void successRequiresSimultaneousAuthoritativeInventoryNotAnAssistantClaim() {
        LiveEvalRun run = started(spec());
        run.event("first_text_delta", json("{\"text\":\"完成\"}"), START + 50_000_000L);
        run.event("assistant", json("{\"text\":\"完成了！\"}"), START + 100_000_000L);
        JsonObject tableOnly = snapshot();
        tableOnly.getAsJsonObject("inventory").addProperty("minecraft:crafting_table", 1);
        run.observe(START + 200_000_000L, tableOnly);
        JsonObject pickOnly = snapshot();
        pickOnly.getAsJsonObject("inventory").addProperty("minecraft:wooden_pickaxe", 1);
        run.observe(START + 300_000_000L, pickOnly);
        assertFalse(run.finished(), "items held at different times are not a completed combined objective");
        run.observe(START + 400_000_000L, completed());
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(50, run.report().get("first_token_ms").getAsDouble());
        assertEquals(100, run.report().get("first_reply_ms").getAsDouble());
        assertTrue(run.report().get("correct_response_ms").isJsonNull());
        assertEquals("not_measured", run.report().get("response_correctness").getAsString());
    }

    @Test
    void lastAllowedModelCallAndItsToolsCanFinishBeforeAnotherCallIsDenied() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        run.used(new Usage(10, 1, 0, 0));
        assertTrue(run.tryModelCall(START + 1));
        run.used(new Usage(10, 1, 0, 0));
        assertTrue(run.beforeTool(START + 2));
        run.observe(START + 3, snapshot());
        assertFalse(run.finished(), "using call N does not cut off its in-flight work");
        assertFalse(run.tryModelCall(START + 4));
        assertEquals(2, run.modelCalls());
        run.observe(START + 5, completed());
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(1, run.report().get("denied_model_calls").getAsInt());
    }

    @Test
    void deniedNextCallEndsUnfinishedWorkOnObservation() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        assertTrue(run.tryModelCall(START + 1));
        assertFalse(run.tryModelCall(START + 2));
        assertFalse(run.beforeTool(START + 3));
        run.observe(START + 4, snapshot());
        outcome(run, "budget_exhausted", "max_model_calls");
        assertFalse(run.report().get("usage_complete").getAsBoolean());
    }

    @Test
    void cacheTokensCountTowardTheExplicitBudget() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        run.used(new Usage(10, 5, 80, 10));
        assertFalse(run.beforeTool(START + 1));
        run.observe(START + 2, completed());
        outcome(run, "budget_exhausted", "max_tokens");
        assertEquals(105, run.report().getAsJsonObject("tokens").get("total").getAsLong());
        assertTrue(run.report().get("cost").isJsonNull());
    }

    @Test
    void exactTokenBudgetStillAllowsCurrentToolsButNoFurtherRequest() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        run.used(new Usage(90, 10, 0, 0));
        assertTrue(run.beforeTool(START + 1));
        assertFalse(run.tryModelCall(START + 2));
        run.observe(START + 3, completed());
        outcome(run, "success", "authoritative_success_criterion_met");
    }

    @Test
    void wallClockDeadlineStopsEvenWhenNoModelReplyArrives() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        run.observe(START + 10_000_000_000L, completed());
        outcome(run, "budget_exhausted", "max_seconds");
        assertFalse(run.tryModelCall(START + 10_000_000_001L));
    }

    @Test
    void deathWinsOverACompletedInventoryEvenAfterRespawn() {
        LiveEvalRun run = started(spec());
        JsonObject resurrected = completed();
        resurrected.addProperty("deaths", 1);
        run.observe(START + 1, resurrected);
        outcome(run, "failed", "companion_died");
    }

    @Test
    void absentDeadBodyDoesNotRequireHealthOrPositionEvidence() {
        JsonObject spec = spec();
        spec.getAsJsonObject("success").addProperty("min_health", 10);
        LiveEvalRun run = started(spec);
        run.observe(START + 1, json("{\"alive\":false,\"deaths\":1,\"health\":null,\"position\":null,\"inventory\":{}}"));
        outcome(run, "failed", "companion_died");
    }

    @Test
    void milestonesKeepFirstObservedEvidenceAfterResourcesAreConsumed() {
        LiveEvalRun run = started(spec());
        JsonObject log = snapshot();
        log.getAsJsonObject("inventory").addProperty("minecraft:oak_log", 1);
        run.observe(START + 100_000_000L, log);
        run.observe(START + 200_000_000L, snapshot());
        run.observe(START + 300_000_000L, log);
        assertEquals(1, run.report().getAsJsonArray("milestones").size());
        JsonObject first = run.report().getAsJsonArray("milestones").get(0).getAsJsonObject();
        assertEquals(100, first.get("elapsed_ms").getAsDouble());
        assertEquals(1, first.getAsJsonObject("snapshot").getAsJsonObject("inventory").get("minecraft:oak_log").getAsInt());
        assertFalse(run.finished());
    }

    @Test
    void missingUsageCannotBeMistakenForFreeUnlimitedCalls() {
        for (Usage usage : new Usage[]{null, Usage.ZERO}) {
            LiveEvalRun run = started(spec());
            assertTrue(run.tryModelCall(START));
            run.used(usage);
            assertFalse(run.tryModelCall(START + 1));
            run.observe(START + 2, snapshot());
            outcome(run, "infra_error", "model_usage_unavailable");
            assertFalse(run.report().get("usage_complete").getAsBoolean());
            assertEquals(1, run.report().get("unknown_usage_reports").getAsInt());
        }
    }

    @Test
    void independentlyObservedSuccessSurvivesAnUnknownFinalUsageReport() {
        LiveEvalRun run = started(spec());
        assertTrue(run.tryModelCall(START));
        run.used(null);
        run.observe(START + 1, completed());
        outcome(run, "success", "authoritative_success_criterion_met");
        assertFalse(run.report().get("usage_complete").getAsBoolean());
    }

    @Test
    void allPhysicalConditionsMustHoldAtTheSameTime() {
        JsonObject spec = spec();
        spec.add("success", json("""
                {"min_health":10,"min_food":8,"dragon_kills":1,
                 "within":{"position":[3,64,4],"radius":1}}
                """));
        LiveEvalRun run = started(spec);
        JsonObject world = snapshot();
        world.addProperty("dragon_kills", 1);
        run.observe(START + 1, world);
        assertFalse(run.finished(), "dragon kill alone is not all the conditions");
        world.add("position", JsonParser.parseString("[3,64,4]"));
        world.addProperty("food", 7);
        run.observe(START + 2, world);
        assertFalse(run.finished());
        world.addProperty("food", 8);
        run.observe(START + 3, world);
        outcome(run, "success", "authoritative_success_criterion_met");
    }

    @Test
    void missingWorldEvidenceIsInfrastructureFailureNotAZeroInventoryScore() {
        LiveEvalRun run = started(spec());
        JsonObject invalid = snapshot();
        invalid.remove("inventory");
        run.observe(START + 1, invalid);
        assertEquals("infra_error", run.report().get("status").getAsString());
        assertTrue(run.report().get("reason").getAsString().startsWith("invalid_snapshot:"));
    }

    @Test
    void rejectsEmptyUnknownAndMalformedConditionsInsteadOfSilentlyPassing() {
        for (String criterion : new String[]{"{}", "{\"items\":{}}", "{\"min_heath\":10}",
                "{\"items\":{\"minecraft:dirt\":1.5}}", "{\"items\":{\"minecraft:dirt\":0}}",
                "{\"within\":{\"position\":[0,1],\"radius\":1}}",
                "{\"within\":{\"position\":[0,1,2],\"radius\":1,\"dimension\":\"end\"}}"}) {
            JsonObject spec = spec();
            spec.add("success", json(criterion));
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(spec), criterion);
        }
    }

    @Test
    void budgetsMustBeExplicitPositiveAndIntegralWhereRequired() {
        for (String key : new String[]{"max_model_calls", "max_seconds", "max_tokens"}) {
            JsonObject missing = spec();
            missing.getAsJsonObject("budget").remove(key);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(missing));
            JsonObject zero = spec();
            zero.getAsJsonObject("budget").addProperty(key, 0);
            assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(zero));
        }
        JsonObject fraction = spec();
        fraction.getAsJsonObject("budget").addProperty("max_model_calls", 1.5);
        assertThrows(IllegalArgumentException.class, () -> new LiveEvalRun(fraction));
    }

    @Test
    void recordsAreDetachedAndFinishedEvidenceCannotBeRewritten() {
        JsonObject spec = spec();
        LiveEvalRun run = started(spec);
        spec.getAsJsonObject("success").getAsJsonObject("items").addProperty("minecraft:diamond", 99);
        JsonObject evidence = completed();
        run.observe(START + 1, evidence);
        evidence.getAsJsonObject("inventory").remove("minecraft:wooden_pickaxe");
        run.report().getAsJsonObject("final_snapshot").getAsJsonObject("inventory").remove("minecraft:wooden_pickaxe");
        run.trace().remove(0);
        run.finish("cancelled", "late cancellation", START + 2);
        assertFalse(run.tryModelCall(START + 5_000_000_000L));
        assertFalse(run.beforeTool(START + 5_000_000_000L));
        outcome(run, "success", "authoritative_success_criterion_met");
        assertEquals(0.000001, run.report().get("elapsed_ms").getAsDouble());
        assertTrue(run.report().getAsJsonObject("final_snapshot").getAsJsonObject("inventory").has("minecraft:wooden_pickaxe"));
        assertEquals(4, run.trace().size());
    }

    @Test
    void incrementalTracePreservesOrderAndDoesNotExposeStoredEvidence() {
        LiveEvalRun run = started(spec());
        int offset = run.trace().size();
        assertTrue(run.traceFrom(offset).isEmpty());
        run.event("checkpoint", json("{\"note\":\"first\"}"), START + 1);
        run.observe(START + 2, snapshot());
        var tail = run.traceFrom(offset);
        assertEquals(2, tail.size());
        assertEquals("checkpoint", tail.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("snapshot", tail.get(1).getAsJsonObject().get("type").getAsString());
        tail.get(0).getAsJsonObject().getAsJsonObject("data").addProperty("note", "changed");
        assertEquals("first", run.traceFrom(offset).get(0).getAsJsonObject()
                .getAsJsonObject("data").get("note").getAsString());
        offset += tail.size();
        run.finish("cancelled", "owner stopped evaluation", START + 3);
        assertEquals("finished", run.traceFrom(offset).get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(run.trace(), run.traceFrom(0));
        assertTrue(run.traceFrom(offset + 1).isEmpty());
    }

    @Test
    void incrementalTraceRejectsInvalidOffsets() {
        LiveEvalRun run = started(spec());
        assertThrows(IllegalArgumentException.class, () -> run.traceFrom(-1));
        assertThrows(IllegalArgumentException.class, () -> run.traceFrom(run.trace().size() + 1));
    }

    @Test
    void cannotFinishWithSuccessWithoutWorldEvidence() {
        LiveEvalRun run = new LiveEvalRun(spec());
        assertThrows(IllegalArgumentException.class, () -> run.finish("success", "assistant claimed done", START));
        run.start(START, snapshot());
        assertThrows(IllegalArgumentException.class, () -> run.finish("success", "assistant claimed done", START + 1));
        run.finish("cancelled", "owner stopped evaluation", START + 2);
        outcome(run, "cancelled", "owner stopped evaluation");
    }

    @Test
    void setupFailureCanBeRecordedWithoutAStartedWorld() {
        LiveEvalRun run = new LiveEvalRun(spec());
        run.finish("infra_error", "provider_unbound", -START);
        outcome(run, "infra_error", "provider_unbound");
        assertTrue(run.report().get("initial_snapshot").isJsonNull());
        assertEquals(0, run.modelCalls());
        assertEquals(0, run.report().get("elapsed_ms").getAsDouble());
    }

    @Test
    void concurrentTransportAttemptsCannotOversubscribeTheBudget() throws Exception {
        LiveEvalRun run = started(spec());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) calls.add(pool.submit(() -> run.tryModelCall(START + 1)));
            int accepted = 0;
            for (var call : calls) if (call.get()) accepted++;
            assertEquals(2, accepted);
        }
        assertEquals(2, run.modelCalls());
        run.observe(START + 2, snapshot());
        outcome(run, "budget_exhausted", "max_model_calls");
    }

    @Test
    void usageOverflowStopsEvaluationInsteadOfWrappingBelowTheBudget() {
        JsonObject spec = spec();
        spec.getAsJsonObject("budget").addProperty("max_tokens", Long.MAX_VALUE);
        LiveEvalRun run = started(spec);
        run.used(new Usage(Long.MAX_VALUE - 1, 0, 0, 0));
        run.used(new Usage(0, 2, 0, 0));
        assertFalse(run.beforeTool(START + 1));
        run.observe(START + 2, snapshot());
        outcome(run, "infra_error", "model_usage_overflow");
        assertEquals(Long.MAX_VALUE - 1, run.report().getAsJsonObject("tokens").get("total").getAsLong());
    }
}
