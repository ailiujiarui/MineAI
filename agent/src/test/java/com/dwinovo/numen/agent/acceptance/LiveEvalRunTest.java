package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.provider.Usage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveEvalRunTest {
    private static final long START = 10_000_000_000L;

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
