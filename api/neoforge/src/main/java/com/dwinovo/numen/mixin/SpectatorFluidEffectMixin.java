package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NeoForge 21.1 moves the water reads into its unmapped, three-argument renderFluid method. */
@Mixin(ScreenEffectRenderer.class)
public abstract class SpectatorFluidEffectMixin {
    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private static double numen$waterX(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getX() : SpectatorClient.target().getX();
    }

    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyeY()D"))
    private static double numen$waterEyeY(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getEyeY() : SpectatorClient.target().getEyeY();
    }

    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private static double numen$waterZ(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getZ() : SpectatorClient.target().getZ();
    }

    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"))
    private static Level numen$waterLevel(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.level() : SpectatorClient.target().level();
    }

    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private static float numen$waterYaw(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getYRot() : SpectatorClient.target().getYRot();
    }

    @Redirect(method = "renderFluid(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;)V", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private static float numen$waterPitch(LocalPlayer owner) {
        return SpectatorClient.target() == null ? owner.getXRot() : SpectatorClient.target().getXRot();
    }
}
