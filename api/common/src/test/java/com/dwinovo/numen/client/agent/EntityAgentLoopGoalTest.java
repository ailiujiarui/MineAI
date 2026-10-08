package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.goal.GoalVerifier;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.program.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目标核对的程序用真实 Lua 参数绑定,回执用线上同一种结局读取,不需要 Minecraft 世界。 */
class EntityAgentLoopGoalTest {

    private static final ScriptCatalog CATALOG = new ScriptCatalog(Map.of("numen.verify", Map.of(
            "have", function(2, Set.of()),
            "block", function(4, Set.of()),
            "near", function(1, Set.of("radius")))), ScriptCatalog.ModuleSource.NONE, Map.of());

    private static ScriptCatalog.Function function(int positions, Set<String> options) {
        return new ScriptCatalog.Function(positions, options, ScriptCatalog.Kind.VALUE, null, null);
    }

    private static ScriptRun.Call call(String claim) {
        ScriptRun run = ScriptEngine.IN_USE.start("goal-verify", EntityAgentLoop.verifyProgram(claim),
                CATALOG, line -> {});
        try {
            return assertInstanceOf(ScriptRun.Call.class, run.start());
        } finally {
            run.close();
        }
    }

    @Test
    void nearRadiusBindsToAnOptionAndOmissionUsesTheApiDefault() {
        ScriptRun.Call explicit = call("near minecraft:chest 7");
        assertEquals("numen.verify.near", explicit.function());
        assertEquals(List.of("minecraft:chest"), explicit.args());
        assertEquals(Map.of("radius", 7L), explicit.options());
        ScriptRun.Call omitted = call("near chest");
        assertEquals(List.of("chest"), omitted.args());
        assertTrue(omitted.options().isEmpty());
    }

    @Test
    void haveAndBlockKeepTheirPositionalArguments() {
        assertEquals(List.of("iron_ingot", 3L), call("have iron_ingot 3").args());
        assertEquals(List.of("iron_ingot"), call("have iron_ingot").args());
        assertEquals(List.of("minecraft:torch", 120L, 64L, -3L),
                call("block minecraft:torch 120 64 -3").args());
    }

    @Test
    void unsupportedOrInvalidClaimsDoNotGenerateAProgram() {
        for (String claim : List.of("", "machine configured", "near chest seven", "near chest 7 extra",
                "have iron_ingot 3 extra", "block torch 1 2", "near chest\");error('injected')")) {
            assertNull(EntityAgentLoop.verifyProgram(claim), claim);
        }
        assertNull(EntityAgentLoop.verifyProgram(null));
    }

    @Test
    void generatedProgramRejectsAWorldMismatchAndReportsExpectedAndActual() {
        ScriptRun run = ScriptEngine.IN_USE.start("goal-verify",
                EntityAgentLoop.verifyProgram("near chest 7"), CATALOG, line -> {});
        try {
            assertInstanceOf(ScriptRun.Call.class, run.start());
            ScriptRun.Done done = assertInstanceOf(ScriptRun.Done.class, run.resume(ScriptRun.Result.ok(
                    Map.of("holds", false, "expected", "chest within 7", "actual", "none within 7"))));
            assertFalse(done.ok());
            GoalVerifier.Result result = EntityAgentLoop.readVerifyRun(RunResult.refused(done.error()));
            assertFalse(result.verified());
            assertTrue(result.measured());
            assertTrue(result.detail().contains("chest within 7"));
            assertTrue(result.detail().contains("none within 7"));
        } finally {
            run.close();
        }
    }

    @Test
    void failedOrMissingRunsAreUnmeasuredAndOnlySuccessfulRunsConfirm() {
        for (RunResult run : List.of(RunResult.refused("dispatch failed"), RunResult.refused("verify timed out"),
                RunResult.refused("bad_argument: radius"), new RunResult.Missing(List.of("missing-module")))) {
            GoalVerifier.Result result = EntityAgentLoop.readVerifyRun(run);
            assertFalse(result.verified());
            assertFalse(result.measured());
        }
        GoalVerifier.Result confirmed = EntityAgentLoop.readVerifyRun(new RunResult.Ended(new Program.Outcome(
                ToolOutcome.success("confirmed"), new ScriptCall.Ending(ScriptCall.Status.OK, null),
                List.of(), List.of(), null)));
        assertTrue(confirmed.verified());
        assertTrue(confirmed.measured());
    }
}
