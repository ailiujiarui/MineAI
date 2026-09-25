package com.dwinovo.numen.agent.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalPredicateTest {

    private static Observation outputs(Map<String, String> outputs) {
        return Observation.of(1L, outputs);
    }

    private static GoalPredicate diamond() {
        return GoalPredicate.all("a diamond block at 0 -60 0", List.of(
                new Probe("blk", "execute if block 0 -60 0 minecraft:diamond_block", Expectation.testPassed()),
                new Probe("count", "data get block 0 -60 0", Expectation.contains("minecraft:diamond_block"))));
    }

    @Test
    void allRequiresEveryProbe() {
        assertTrue(diamond().evaluate(outputs(Map.of(
                "blk", "Test passed",
                "count", "{Items:[{id:\"minecraft:diamond_block\"}]}"))).met());
        assertFalse(diamond().evaluate(outputs(Map.of(
                "blk", "Test passed",
                "count", "{Items:[]}"))).met());
    }

    @Test
    void anyNeedsOneProbe() {
        GoalPredicate either = GoalPredicate.any("a torch or a lantern", List.of(
                new Probe("torch", "execute if block 1 -60 1 minecraft:torch", Expectation.testPassed()),
                new Probe("lantern", "execute if block 1 -60 1 minecraft:lantern", Expectation.testPassed())));
        assertTrue(either.evaluate(outputs(Map.of("torch", "Test failed", "lantern", "Test passed"))).met());
        assertFalse(either.evaluate(outputs(Map.of("torch", "Test failed", "lantern", "Test failed"))).met());
    }

    @Test
    void anEmptyPredicateIsNeverMet() {
        assertFalse(GoalPredicate.all("nothing at all", List.of()).evaluate(outputs(Map.of())).met());
    }

    @Test
    void anUnreachableRangeNeverProvesAnything() {
        AcceptanceVerdict verdict = diamond().evaluate(Observation.unreachable(1L, "connection refused"));
        assertFalse(verdict.met());
        assertTrue(verdict.reason().contains("connection refused"));
    }

    @Test
    void jsonRoundTripKeepsConditionAndProbes() {
        GoalPredicate back = GoalPredicate.fromJson(diamond().toJson());
        assertEquals("a diamond block at 0 -60 0", back.condition());
        assertEquals(2, back.probes().size());
        assertTrue(back.probes().get(0).expectation().matches("x Test passed y"));
    }

    // ---- MineAI 式库存目标 ----

    @Test
    void objectiveCountsTheAuthoritativeHolding() {
        GoalPredicate haveThree = GoalPredicate.objective(
                Objective.haveItem("minecraft:iron_ingot", 3), "numen", 0);
        assertEquals("clear numen minecraft:iron_ingot 0", haveThree.probes().get(0).command());
        assertTrue(haveThree.evaluate(outputs(Map.of("have", "Found 3 matching item(s) on player numen"))).met());
        assertFalse(haveThree.evaluate(outputs(Map.of("have", "Found 2 matching item(s) on player numen"))).met());
        assertFalse(haveThree.evaluate(outputs(Map.of("have", "No items were found on player numen"))).met());
    }

    @Test
    void relativeObjectiveHonoursTheBaseline() {
        // 受理时手里已经有 2 个,目标是"再拿 3 个"。
        GoalPredicate gain = GoalPredicate.objective(
                Objective.gainItem("minecraft:iron_ore", 3), "numen", 2);
        assertFalse(gain.evaluate(outputs(Map.of("have", "Found 4 matching item(s) on player numen"))).met(),
                "4 - baseline 2 = 2, not 3 yet");
        assertTrue(gain.evaluate(outputs(Map.of("have", "Found 5 matching item(s) on player numen"))).met());
    }

    @Test
    void anUnreachableRangeDoesNotFakeAnObjective() {
        GoalPredicate have = GoalPredicate.objective(Objective.haveItem("minecraft:dirt", 1), "numen", 0);
        assertFalse(have.evaluate(Observation.unreachable(1L, "refused")).met());
    }

    @Test
    void objectiveJsonRoundTrip() {
        GoalPredicate have = GoalPredicate.objective(Objective.haveItem("minecraft:dirt", 1), "numen", 0);
        GoalPredicate back = GoalPredicate.fromJson(have.toJson());
        assertTrue(back instanceof ObjectivePredicate);
        assertTrue(back.evaluate(outputs(Map.of("have", "Found 1 matching item(s) on player numen"))).met());
    }
}
