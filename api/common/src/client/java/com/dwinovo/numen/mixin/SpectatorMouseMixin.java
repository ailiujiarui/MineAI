package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class SpectatorMouseMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void numen$lockViewToTarget(double timeDelta, CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void numen$noScrollSelection(long window, double x, double y, CallbackInfo ci) {
        if (SpectatorClient.active() && Minecraft.getInstance().screen == null) ci.cancel();
    }
}
