package com.dwinovo.numen.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobReplayTest {
    private record Args(String target) {}

    @Test
    void explicitRecipeAndReplacementArgumentsAreExclusiveAndImmutable() {
        Job<String> original = Job.done("done");
        Job<String> args = original.replayedAs(new Args("uuid"));
        Job<String> recipe = args.replayedAs("local p = numen.route.plan({to = {x = 1, y = 64, z = 2}})\nnumen.move.go(p)");
        assertNull(original.replay());
        assertNull(original.replayLua());
        assertEquals(new Args("uuid"), args.replay());
        assertNull(args.replayLua());
        assertNull(recipe.replay());
        assertTrue(recipe.replayLua().contains("numen.move.go(p)"));
        assertEquals("done", recipe.done());
        assertNull(recipe.replayedAs(new Args("other")).replayLua());
        assertThrows(NullPointerException.class, () -> original.replayedAs((String) null));
    }
}
