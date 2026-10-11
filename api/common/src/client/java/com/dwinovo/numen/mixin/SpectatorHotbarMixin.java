package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import com.dwinovo.numen.client.spectator.SpectatorHud;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both loaders call the vanilla spectator hotbar even when they split the HUD into layers. */
@Mixin(SpectatorGui.class)
public abstract class SpectatorHotbarMixin {
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void numen$targetHud(GuiGraphics graphics, CallbackInfo ci) {
        if (SpectatorClient.active()) {
            SpectatorHud.render(graphics);
            ci.cancel();
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void numen$hideSpectatorSelection(GuiGraphics graphics, CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }
}
