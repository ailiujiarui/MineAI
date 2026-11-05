package com.dwinovo.numen.api.client.spectator;

import com.dwinovo.numen.api.client.hud.SpeechBubbles;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import net.minecraft.client.resources.language.NumenTestLanguage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectatorSpeechHudTest {
    @BeforeAll
    static void language() {
        NumenTestLanguage.install();
    }

    @Test
    void ordinaryActivityAndEmptySnapshotsDrawNothingIncludingNoBackdrop() {
        var surface = new Surface();
        draw(surface, null);
        draw(surface, new SpeechBubbles.View(null, 0, 0, false, "dig 64 dirt", false, null));
        assertTrue(surface.texts.isEmpty());
        assertTrue(surface.rects.isEmpty());
    }

    @Test
    void speechRemainsVisibleWhileTheBodyWorksWithoutRepeatingTheActivity() {
        var surface = new Surface();
        draw(surface, new SpeechBubbles.View("I will build by the lake.", 1, 1, false, "dig 64 dirt", false, null));
        assertEquals(List.of("I will build by the lake."), surface.words());
    }

    @Test
    void thinkingAndConsentUseTheExistingBubbleTranslations() {
        var thinking = new Surface();
        draw(thinking, new SpeechBubbles.View(null, 0, 0, false, null, true, null));
        assertEquals(List.of("✦ Thinking"), thinking.words());
        var consent = new Surface();
        draw(consent, new SpeechBubbles.View(null, 0, 0, true, "dig", true, "model failure"));
        assertEquals(List.of("⌛ Waiting for your OK"), consent.words());
    }

    @Test
    void explicitFailureTakesPriorityOverThinkingAndVisibleBodyWork() {
        var surface = new Surface();
        draw(surface, new SpeechBubbles.View(null, 0, 0, false, "dig", true, "Previous action failed"));
        assertEquals(List.of("Previous action failed"), surface.words());
    }

    @Test
    void expiredSpeechLeavesNoPlateButCanStillShowAPendingConfirmation() {
        var surface = new Surface();
        draw(surface, new SpeechBubbles.View("finished", 1, 0, false, null, false, null));
        assertTrue(surface.texts.isEmpty());
        assertTrue(surface.rects.isEmpty());
        draw(surface, new SpeechBubbles.View("finished", 1, 0, true, null, false, null));
        assertEquals(List.of("⌛ Waiting for your OK"), surface.words());
    }

    @Test
    void longRepliesAndStatusesFitTheScreenAndShareAtMostTwoLines() {
        var surface = new Surface();
        var bubble = new SpeechBubbles.View("long reply ".repeat(60), 1, 1, true, "dig", false, null);
        SpectatorSpeechHud.draw(surface, bubble, 160, 300, 200);
        assertEquals(2, surface.texts.size());
        assertTrue(surface.texts.getFirst().text().endsWith("..."));
        for (Text text : surface.texts) {
            assertTrue(text.x() >= 12);
            assertTrue(text.x() + surface.textWidth(text.text()) <= 148);
            assertTrue(text.y() > 150);
            assertTrue(text.y() + surface.lineHeight() <= 200);
        }
        assertFalse(surface.words().contains("dig"));
    }

    @Test
    void smallGuiKeepsTheNecessaryStatusAndNeverCoversTheCrosshair() {
        var surface = new Surface();
        var bubble = new SpeechBubbles.View("a speech", 1, 1, true, null, false, null);
        SpectatorSpeechHud.draw(surface, bubble, 320, 200, 122);
        assertEquals(List.of("⌛ Waiting for your OK"), surface.words());
        var tooShort = new Surface();
        SpectatorSpeechHud.draw(tooShort, bubble, 320, 150, 68);
        assertTrue(tooShort.texts.isEmpty());
        assertTrue(tooShort.rects.isEmpty());
    }

    @Test
    void theSubtitleUsesTheSameOpacityAsTheHeadBubble() {
        var surface = new Surface();
        draw(surface, new SpeechBubbles.View("hello", .5f, .5f, false, null, false, null));
        assertEquals(64, surface.texts.getFirst().color() >>> 24);
    }

    private static void draw(Surface surface, SpeechBubbles.View bubble) {
        SpectatorSpeechHud.draw(surface, bubble, 640, 360, 278);
    }

    private record Text(String text, int x, int y, int color) {}

    private static final class Surface implements IDrawSurface {
        final List<Text> texts = new ArrayList<>();
        final List<int[]> rects = new ArrayList<>();

        List<String> words() { return texts.stream().map(Text::text).toList(); }
        @Override public void fillRect(int x, int y, int w, int h, int color) { rects.add(new int[]{x, y, w, h}); }
        @Override public void drawText(String text, int x, int y, int color, boolean shadow) { texts.add(new Text(text, x, y, color)); }
        @Override public int textWidth(String text) { return text.codePointCount(0, text.length()) * 6; }
        @Override public int lineHeight() { return 10; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}
    }
}
