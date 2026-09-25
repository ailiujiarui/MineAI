package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.loop.LoopHarness;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopPlannerPortTest extends LoopHarness {

    @Test
    void aPlanCompletesWithTheModelAnswer() {
        LoopPlannerPort port = new LoopPlannerPort(loop, "you are a planner");
        CompletableFuture<String> future = port.plan("break the goal into steps");

        assertEquals("you are a planner", model.last().request().systemPrompt());
        assertTrue(model.last().lastUser().contains("break the goal into steps"));

        model.last().say("STEP 1: get wood");
        assertEquals("STEP 1: get wood", future.join());
    }

    @Test
    void aFailedCallFailsTheFutureSoTheTreeCanFallBack() {
        LoopPlannerPort port = new LoopPlannerPort(loop, "s");
        CompletableFuture<String> future = port.plan("x");
        model.last().fail("boom");
        assertTrue(future.isCompletedExceptionally());
    }
}
