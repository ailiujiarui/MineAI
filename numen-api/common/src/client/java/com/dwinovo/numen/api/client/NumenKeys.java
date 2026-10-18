package com.dwinovo.numen.api.client;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.client.agent.Conversations;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.chat.CompanionChatScreen;
import com.dwinovo.numen.api.client.chat.CompanionWheelScreen;
import com.dwinovo.numen.api.client.chat.QuickVoice;
import com.dwinovo.numen.api.client.chat.SelectedCompanion;
import com.dwinovo.numen.api.client.screen.NumenScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared key mappings: defined once here, registered by each loader's client
 * init (Fabric {@code KeyMappingHelper} / NeoForge {@code RegisterKeyMappingsEvent}),
 * polled once per client tick via {@link #tick()}.
 *
 * <p>快捷交互三件套围绕「当前交互对象」({@link SelectedCompanion}):
 * 轮盘选人 → 对话键发文字 → 语音键按住说话。默认键位都避开原版占用,
 * Controls 的 Numen 区可随意改绑。
 */
public final class NumenKeys {

    /** Dedicated vanilla Controls category so the binding is easy to find and rebind (Options → Controls → Numen). */
    public static final String CATEGORY = com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_CATEGORY_NUMEN;

    /** G — open the companion roster panel (or straight into chat with a single pet). */
    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_OPEN_ROSTER,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            CATEGORY);

    /** R(按住)— 同伴轮盘:滚轮/指向选中当前交互对象,松开确认。 */
    public static final KeyMapping COMPANION_WHEEL = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_COMPANION_WHEEL,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R,
            CATEGORY);

    /** Y — 快捷对话:对当前交互对象弹极简输入框(准星指着谁则优先谁)。 */
    public static final KeyMapping TALK_COMPANION = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_TALK_COMPANION,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
            CATEGORY);

    /** V(按住)— 快捷语音:对讲机式,松开把转写发给当前交互对象。 */
    public static final KeyMapping QUICK_VOICE = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_QUICK_VOICE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
            CATEGORY);

    /** O — watch the selected companion; press again to leave, or select another to switch. */
    public static final KeyMapping WATCH_COMPANION = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_WATCH_COMPANION,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);

    /** [ — 观看上一位活着的伙伴;正在观看中按它直接切过去。 */
    public static final KeyMapping WATCH_PREVIOUS = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_WATCH_PREVIOUS,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);

    /** ] — 观看下一位活着的伙伴;正在观看中按它直接切过去。 */
    public static final KeyMapping WATCH_NEXT = new KeyMapping(
            com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_WATCH_NEXT,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);

    private static boolean voiceWasDown;

    private NumenKeys() {}

    /** Per-client-tick poll; key presses only register while no screen is open. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        while (WATCH_COMPANION.consumeClick()) {
            if (mc.player != null && (mc.screen == null || com.dwinovo.numen.api.spectator.SpectatorMenuClient.viewingScreen())) {
                var selected = com.dwinovo.numen.api.client.spectator.SpectatorClient.active()
                        ? SelectedCompanion.get() : SelectedCompanion.resolveTarget();
                if (com.dwinovo.numen.api.client.spectator.SpectatorClient.active()
                        && (selected == null || selected.members().size() != 1
                        || com.dwinovo.numen.api.client.spectator.SpectatorClient.accepts(
                                com.dwinovo.numen.api.client.spectator.SpectatorClient.sessionId(), selected.members().getFirst()))) {
                    com.dwinovo.numen.api.client.spectator.SpectatorClient.exit();
                } else if (selected != null && selected.members().size() == 1) {
                    com.dwinovo.numen.api.client.spectator.SpectatorClient.enter(selected.members().getFirst());
                } else {
                    com.dwinovo.numen.api.client.hud.TalkHint.flash(I18n.get(Keys.KEYS_PICK_FIRST,
                            COMPANION_WHEEL.getTranslatedKeyMessage().getString()), 3000);
                }
            }
        }
        while (WATCH_PREVIOUS.consumeClick()) {
            stepWatched(-1);
        }
        while (WATCH_NEXT.consumeClick()) {
            stepWatched(1);
        }
        while (OPEN_ROSTER.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                NumenScreen.openWorkspace();
            }
        }
        while (COMPANION_WHEEL.consumeClick()) {
            if (mc.player == null || mc.screen != null) {
                continue;
            }
            if (NumenRoster.instance().entries().isEmpty()) {
                com.dwinovo.numen.api.client.hud.TalkHint.flash(I18n.get(Keys.KEYS_NO_COMPANION), 3000);
                continue;
            }
            mc.setScreen(new CompanionWheelScreen());
        }
        while (TALK_COMPANION.consumeClick()) {
            if (mc.player == null || mc.screen != null) {
                continue;
            }
            // 有征询挂着先答征询:对象是最早在等的那位,不看准星——右上角弹出来说按这个键答的就是她
            var asking = com.dwinovo.numen.api.client.consent.ConsentCards.first();
            if (asking != null) {
                mc.setScreen(new CompanionChatScreen(Conversations.instance().of(asking.companion())));
                continue;
            }
            Conversation target = SelectedCompanion.resolveTarget();
            if (target == null) {
                com.dwinovo.numen.api.client.hud.TalkHint.flash(I18n.get(Keys.KEYS_PICK_FIRST,
                        COMPANION_WHEEL.getTranslatedKeyMessage().getString()), 3000);
                continue;
            }
            mc.setScreen(new CompanionChatScreen(target));
        }
        // 快捷语音:按下沿开录,抬起沿(或任何界面弹开)收音发送
        boolean voiceDown = QUICK_VOICE.isDown() && mc.player != null && mc.screen == null;
        if (voiceDown && !voiceWasDown) {
            QuickVoice.press();
        } else if (!voiceDown && voiceWasDown) {
            QuickVoice.release();
        }
        voiceWasDown = voiceDown;
    }

    /**
     * [ / ] 沿名册走一位:活着的同伴按服务端排好的名字序成环,观看中从当前订阅的她出发,
     * 没在观看则从转盘选中的她出发(没选过就从环的头/尾进)。切换由服务端换订阅完成,
     * 不退出旁观,也不打断观看。
     */
    private static void stepWatched(int direction) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (mc.screen != null
                && !com.dwinovo.numen.api.spectator.SpectatorMenuClient.viewingScreen())) {
            return;
        }
        List<UUID> ring = new ArrayList<>();
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (!entry.dead()) {
                ring.add(entry.uuid());
            }
        }
        if (ring.isEmpty()) {
            com.dwinovo.numen.api.client.hud.TalkHint.flash(
                    I18n.get(NumenRoster.instance().entries().isEmpty()
                            ? Keys.KEYS_NO_COMPANION : Keys.KEYS_NO_LIVING_COMPANION), 3000);
            return;
        }
        UUID current = null;
        if (com.dwinovo.numen.api.client.spectator.SpectatorClient.active()) {
            var state = com.dwinovo.numen.api.client.spectator.SpectatorClient.state();
            if (state != null) {
                current = state.target();
            }
        } else {
            Conversation selected = SelectedCompanion.get();
            if (selected != null && selected.members().size() == 1) {
                current = selected.members().getFirst();
            }
        }
        int index = current == null ? -1 : ring.indexOf(current);
        UUID next = index < 0
                ? (direction > 0 ? ring.getFirst() : ring.getLast())
                : ring.get((index + direction + ring.size()) % ring.size());
        com.dwinovo.numen.api.client.spectator.SpectatorClient.enter(next);
    }
}
