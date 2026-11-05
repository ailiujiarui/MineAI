package com.dwinovo.numen.api.client.spectator;

import com.dwinovo.numen.api.client.hud.SpeechBubbles;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.TextWrap;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.network.payload.SpectatorStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

import java.util.List;

/** 第一视角的轻字幕:和头顶气泡读同一份内容,普通干活不占屏幕。 */
final class SpectatorSpeechHud {
    private static final int MAX_WIDTH = 360;
    private static final int MAX_LINES = 2;

    private SpectatorSpeechHud() {}

    static void render(GuiGraphics graphics, SpectatorStatePayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson()) return;
        SpeechBubbles.View bubble = SpeechBubbles.view(SpectatorClient.target().getUUID());
        int heartRows = Math.max(1, (Mth.ceil(state.maxHealth() / 2F) + 9) / 10);
        // 原版动作栏在底部 72px 左右;多排心形还会向上长,字幕两处都让开。
        int bottom = Math.min(graphics.guiHeight() - 82,
                graphics.guiHeight() - 39 - (heartRows - 1) * 10 - 6);
        draw(new McDrawSurface(graphics, mc.font), bubble,
                graphics.guiWidth(), graphics.guiHeight(), bottom);
    }

    /** 只画这帧的快照,不留字幕缓存;切换目标、退出观看不会带走上一只的话。 */
    static void draw(IDrawSurface surface, SpeechBubbles.View bubble, int width, int height, int bottom) {
        if (bubble == null) return;
        String status = bubble.asking() ? I18n.get("numen.bubble.asking")
                : bubble.failure() != null ? bubble.failure()
                : bubble.waiting() ? I18n.get("numen.bubble.thinking") : null;
        float textAlpha = bubble.textIn() * bubble.textOut();
        boolean speech = bubble.hasText() && textAlpha >= 0.05f;
        if (!speech && status == null) return;

        int lineHeight = surface.lineHeight();
        // 小窗口或高 GUI 缩放下宁可少画一行,也不把字幕长到准星上。
        int lines = Math.min(MAX_LINES, (bottom - height / 2 - 12) / lineHeight);
        int maxWidth = Math.min(MAX_WIDTH, width - 24);
        if (lines <= 0 || maxWidth <= 0) return;
        if (status != null) status = TextClip.fit(surface, status, maxWidth);
        if (status != null && status.isBlank()) status = null;
        int speechLines = lines - (status == null ? 0 : 1);
        List<String> spoken = speech && speechLines > 0
                ? TextWrap.wrap(bubble.text(), maxWidth, surface::textWidth, speechLines) : List.of();
        int y = bottom - (spoken.size() + (status == null ? 0 : 1)) * lineHeight;
        for (String line : spoken) {
            drawLine(surface, line, width, y, 0xFFFFFF, textAlpha);
            y += lineHeight;
        }
        if (status != null) {
            int color = bubble.asking() || bubble.failure() != null ? 0xFFD79A : 0xD8D8D8;
            drawLine(surface, status, width, y, color, 1f);
        }
    }

    private static void drawLine(IDrawSurface surface, String text, int width, int y, int rgb, float opacity) {
        if (text.isBlank()) return;
        int textWidth = surface.textWidth(text);
        int x = (width - textWidth) / 2;
        int alpha = Math.round(255 * opacity);
        // 底色只贴着这一行文字,没有内容就没有空框;形状仍走统一画法。
        NumenStyle.box(surface, x - 3, y - 1, textWidth + 6, surface.lineHeight(),
                (Math.round(40 * opacity) << 24), 0);
        surface.drawText(text, x, y, alpha << 24 | rgb, true);
    }
}
