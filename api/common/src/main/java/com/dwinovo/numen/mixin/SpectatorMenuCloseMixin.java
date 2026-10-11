package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.spectator.SpectatorMenuBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class SpectatorMenuCloseMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void numen$openMirroredMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> ci) {
        if (ci.getReturnValue().isPresent() && (Object) this instanceof NumenPlayer target) {
            SpectatorMenuBridge.opened(target);
        }
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void numen$captureClosingMenu(CallbackInfo ci) {
        if ((Object) this instanceof NumenPlayer target) SpectatorMenuBridge.closing(target);
    }
}
