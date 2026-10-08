package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import com.dwinovo.numen.client.spectator.SpectatorHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class SpectatorGuiMixin {
    @Inject(method = "renderCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void numen$hideUnboundOverlays(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (SpectatorClient.active() && SpectatorClient.target() == null) ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void numen$targetCrosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (SpectatorClient.active()) {
            SpectatorHud.renderCrosshair(graphics);
            ci.cancel();
        }
    }

    @Inject(method = {"renderExperienceLevel", "renderEffects"}, at = @At("HEAD"), cancellable = true)
    private void numen$ownerIndicators(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }

    @Redirect(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
    private boolean numen$targetScope(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.isScoping() : SpectatorClient.target().isScoping();
    }

    @Redirect(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"))
    private Inventory numen$targetHelmet(LocalPlayer owner) {
        return SpectatorClient.mirrorInventory() == null ? owner.getInventory() : SpectatorClient.mirrorInventory();
    }

    @Redirect(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getTicksFrozen()I"))
    private int numen$targetFrozenTicks(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getTicksFrozen() : SpectatorClient.target().getTicksFrozen();
    }

    @Redirect(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getPercentFrozen()F"))
    private float numen$targetFrozenAmount(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getPercentFrozen() : SpectatorClient.target().getPercentFrozen();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void numen$noOwnerPortalOverlay(GuiGraphics graphics, float intensity, CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }
}
