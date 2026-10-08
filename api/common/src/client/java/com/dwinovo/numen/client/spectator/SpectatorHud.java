package com.dwinovo.numen.client.spectator;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.network.payload.SpectatorStatePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Original HUD sprites with authoritative NPC data, without changing LocalPlayer. */
public final class SpectatorHud {
    private SpectatorHud() {}

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.withDefaultNamespace("hud/" + name);
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || SpectatorClient.target() == null) return;
        SpectatorStatePayload state = SpectatorClient.state();
        Inventory inventory = SpectatorClient.mirrorInventory();
        if (state == null || inventory == null) return;
        int center = graphics.guiWidth() / 2, bottom = graphics.guiHeight();
        RenderSystem.enableBlend();
        graphics.blitSprite(sprite("hotbar"), center - 91, bottom - 22, 182, 22);
        graphics.blitSprite(sprite("hotbar_selection"), center - 92 + inventory.selected * 20, bottom - 23, 24, 23);
        for (int i = 0; i < 9; i++) drawItem(graphics, inventory.getItem(i), center - 88 + i * 20, bottom - 19);
        ItemStack offhand = inventory.getItem(40);
        if (!offhand.isEmpty()) {
            boolean left = SpectatorClient.target().getMainArm() == HumanoidArm.RIGHT;
            graphics.blitSprite(sprite(left ? "hotbar_offhand_left" : "hotbar_offhand_right"),
                    left ? center - 120 : center + 91, bottom - 23, 29, 24);
            drawItem(graphics, offhand, left ? center - 117 : center + 101, bottom - 19);
        }
        int hearts = Mth.ceil(state.maxHealth() / 2F);
        for (int i = 0; i < hearts; i++) {
            int x = center - 91 + i % 10 * 8, y = bottom - 39 - i / 10 * 10;
            graphics.blitSprite(sprite("heart/container"), x, y, 9, 9);
            if (state.health() > i * 2) graphics.blitSprite(sprite(state.health() >= i * 2 + 2 ? "heart/full" : "heart/half"), x, y, 9, 9);
        }
        for (int i = 0; i < 10; i++) {
            int x = center + 82 - i * 8, y = bottom - 39;
            graphics.blitSprite(sprite("food_empty"), x, y, 9, 9);
            if (state.food() > i * 2) graphics.blitSprite(sprite(state.food() >= i * 2 + 2 ? "food_full" : "food_half"), x, y, 9, 9);
        }
        graphics.blitSprite(sprite("experience_bar_background"), center - 91, bottom - 29, 182, 5);
        int progress = Mth.clamp((int) (state.experienceProgress() * 183), 0, 182);
        if (progress > 0) graphics.blitSprite(sprite("experience_bar_progress"), 182, 5, 0, 0, center - 91, bottom - 29, progress, 5);
        if (state.experienceLevel() > 0) {
            String level = Integer.toString(state.experienceLevel());
            int x = center - mc.font.width(level) / 2, y = bottom - 35;
            graphics.drawString(mc.font, level, x + 1, y, 0, false);
            graphics.drawString(mc.font, level, x - 1, y, 0, false);
            graphics.drawString(mc.font, level, x, y + 1, 0, false);
            graphics.drawString(mc.font, level, x, y - 1, 0, false);
            graphics.drawString(mc.font, level, x, y, 0x80FF20, false);
        }
        String hint = SpectatorClient.target().getName().getString() + " · ["
                + NumenKeys.WATCH_COMPANION.getTranslatedKeyMessage().getString() + "] 退出观看 · ["
                + mc.options.keyInventory.getTranslatedKeyMessage().getString() + "] 背包(只读)";
        graphics.drawCenteredString(mc.font, hint, center, 8, 0xFFFFFFFF);
        RenderSystem.disableBlend();
    }

    private static void drawItem(GuiGraphics graphics, ItemStack item, int x, int y) {
        if (item.isEmpty()) return;
        graphics.renderItem(SpectatorClient.target(), item, x, y, 0);
        graphics.renderItemDecorations(Minecraft.getInstance().font, item, x, y);
    }

    public static void renderCrosshair(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !mc.options.getCameraType().isFirstPerson() || SpectatorClient.target() == null) return;
        RenderSystem.enableBlend();
        graphics.blitSprite(sprite("crosshair"), (graphics.guiWidth() - 15) / 2, (graphics.guiHeight() - 15) / 2, 15, 15);
        // The server samples attack strength each tick; this is not reconstructed from owner clicks.
        float strength = SpectatorClient.state().attackStrength();
        if (mc.options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.CROSSHAIR && strength < 1F) {
            int x = graphics.guiWidth() / 2 - 8, y = graphics.guiHeight() / 2 + 9;
            graphics.blitSprite(sprite("crosshair_attack_indicator_background"), x, y, 16, 4);
            int progress = Mth.clamp((int) (strength * 17), 0, 16);
            if (progress > 0) graphics.blitSprite(sprite("crosshair_attack_indicator_progress"), 16, 4, 0, 0, x, y, progress, 4);
        }
        RenderSystem.disableBlend();
    }
}
