package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessReportTest {

    @Test
    void reportCarriesTheWholeEvidenceChain() throws Exception {
        GoalPredicate predicate = GoalPredicate.objective(
                Objective.haveItem("minecraft:iron_ingot", 3), "numen", 0);
        AcceptanceGoal goal = AcceptanceGoal.of("hold 3 iron ingots", predicate, Whitelist.harnessDefault(), 100L);
        goal.countTurn();
        goal.addTokens(42);

        ActionRecord action = new ActionRecord(1, "c1", "mine", "{}", "{\"success\":true}", true, false, 150L);
        AcceptanceVerdict verdict = predicate.evaluate(Observation.of(200L,
                Map.of("have", "Found 3 matching item(s) on player numen")));

        HarnessReport report = HarnessReport.of("iron-smoke", goal, verdict, 1, 0,
                List.of(action), HarnessReport.FINISHED, 1300L);
        JsonObject json = report.toJson();

        assertEquals("iron-smoke", json.get("scenario").getAsString());
        assertEquals("hold 3 iron ingots", json.get("objective").getAsString());
        assertEquals("3x minecraft:iron_ingot in inventory", json.get("condition").getAsString());
        assertTrue(json.has("predicate"));
        assertTrue(json.has("deniedCommandRoots"));
        assertEquals(1200L, json.get("durationMs").getAsLong());
        assertEquals(1L, json.get("seconds").getAsLong());
        assertEquals(HarnessReport.FINISHED, json.get("state").getAsString());
        assertTrue(json.get("verified").getAsBoolean());
        assertEquals(1, json.get("falseClaims").getAsInt());
        assertEquals(0, json.get("aborts").getAsInt());
        assertEquals(1, json.get("turns").getAsInt());
        assertEquals(42L, json.get("tokensUsed").getAsLong());
        assertEquals(1, json.getAsJsonArray("actions").size());
        assertEquals(1, json.getAsJsonArray("finalProbes").size());
    }

    @Test
    void appendsOneJsonLinePerRun() throws Exception {
        GoalPredicate predicate = GoalPredicate.all("have dirt", List.of());
        AcceptanceGoal goal = AcceptanceGoal.of("dig dirt", predicate, Whitelist.harnessDefault(), 1L);
        HarnessReport report = HarnessReport.of("dig", goal, null, 2, 1, List.of(),
                HarnessReport.FAILED, 2L);

        Path file = Files.createTempFile("numen-harness", ".jsonl");
        try {
            report.appendJsonl(file);
            report.appendJsonl(file);
            List<String> lines = Files.readAllLines(file);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).startsWith("{"));
            assertTrue(lines.get(0).contains("\"falseClaims\":2"));
            assertTrue(lines.get(0).contains("\"aborts\":1"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
