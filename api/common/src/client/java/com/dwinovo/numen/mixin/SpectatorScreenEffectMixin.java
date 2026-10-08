package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Camera-space water, fire and obstructing-block overlays use the companion's body. */
@Mixin(ScreenEffectRenderer.class)
public abstract class SpectatorScreenEffectMixin {
    @ModifyVariable(method = "renderScreenEffect", at = @At("STORE"), ordinal = 0)
    private static Player numen$overlayBody(Player owner) {
        return SpectatorClient.target() == null ? owner : SpectatorClient.target();
    }

    @Redirect(method = "renderScreenEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private static boolean numen$targetSpectator(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.isSpectator() : SpectatorClient.target().isSpectator();
    }

    @Redirect(method = "renderScreenEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
    private static boolean numen$targetFluid(LocalPlayer owner, TagKey<Fluid> fluid) {
        return SpectatorClient.target() == null ? owner.isEyeInFluid(fluid) : SpectatorClient.target().isEyeInFluid(fluid);
    }

    @Redirect(method = "renderScreenEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isOnFire()Z"))
    private static boolean numen$targetFire(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.isOnFire() : SpectatorClient.target().isOnFire();
    }
}
