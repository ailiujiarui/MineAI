package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.script.ScriptCall;
import com.dwinovo.numen.api.client.hud.SpeechBubbles;
import net.minecraft.client.resources.language.NumenTestLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TurnPresenterSpeechTest {
    private static final LlmToolCall PROGRAM = new LlmToolCall("program", "lua", "{}");

    @BeforeAll
    static void language() {
        NumenTestLanguage.install();
    }

    @AfterEach
    void clear() {
        SpeechBubbles.clear();
    }

    @Test
    void anUncaughtProgramErrorIsSharedByBothViewsAndClearedWhenAnotherActionStarts() {
        UUID companion = UUID.randomUUID();
        TurnPresenter presenter = presenter(companion);
        SpeechBubbles.say(companion, "I will build here.");
        presenter.on(new LoopEvent.ProgramEnded(1, PROGRAM,
                new ScriptCall.Ending(ScriptCall.Status.ERROR, "unreachable"), 1));
        assertEquals("I will build here.", SpeechBubbles.view(companion).text());
        assertEquals("Previous action failed", SpeechBubbles.view(companion).failure());
        assertNull(SpeechBubbles.view(UUID.randomUUID()));

        presenter.on(new LoopEvent.ToolStarted(1, PROGRAM));
        assertNull(SpeechBubbles.view(companion).failure());
        assertEquals("I will build here.", SpeechBubbles.view(companion).text());
    }

    @Test
    void successfulStoppedAndRetriableRunsAreNotReportedAsActionFailures() {
        UUID companion = UUID.randomUUID();
        TurnPresenter presenter = presenter(companion);
        for (ScriptCall.Status status : List.of(ScriptCall.Status.OK, ScriptCall.Status.STOPPED)) {
            presenter.on(new LoopEvent.ProgramEnded(1, PROGRAM, new ScriptCall.Ending(status, null), 1));
            assertNull(SpeechBubbles.view(companion));
        }
        presenter.on(new LoopEvent.RunEnded(1, new RunEnd.Failed(Cause.of(Cause.NETWORK), true)));
        assertNull(SpeechBubbles.view(companion));
    }

    @Test
    void newSpeechAndStoppingClearTheOldFailureWithoutLeakingAcrossCompanions() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        TurnPresenter presenter = presenter(first);
        presenter.on(new LoopEvent.ProgramEnded(1, PROGRAM,
                new ScriptCall.Ending(ScriptCall.Status.ERROR, "unreachable"), 1));
        SpeechBubbles.say(second, "Still working.");
        assertNull(SpeechBubbles.view(second).failure());
        SpeechBubbles.say(first, "I found another way.");
        assertNull(SpeechBubbles.view(first).failure());

        presenter.on(new LoopEvent.ProgramEnded(1, PROGRAM,
                new ScriptCall.Ending(ScriptCall.Status.ERROR, "unreachable"), 1));
        presenter.on(new LoopEvent.Halted(HaltReason.OWNER_STOP));
        assertNull(SpeechBubbles.view(first));
        assertEquals("Still working.", SpeechBubbles.view(second).text());
        SpeechBubbles.clear();
        assertNull(SpeechBubbles.view(second));
    }

    private static TurnPresenter presenter(UUID companion) {
        return new TurnPresenter(companion,
                () -> new LoopStatus(null, null, null, false, List.of(), 0, null), () -> "Companion");
    }
}
