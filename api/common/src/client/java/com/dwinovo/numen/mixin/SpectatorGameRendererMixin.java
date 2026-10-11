package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import com.dwinovo.numen.client.spectator.SpectatorHandRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 使用原版手持物的投影、光照与第一人称/F1 条件,只替换观看数据源。 */
@Mixin(GameRenderer.class)
public abstract class SpectatorGameRendererMixin {
    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType numen$showTargetHands(MultiPlayerGameMode mode) {
        return SpectatorClient.active()
                ? (SpectatorClient.target() != null ? GameType.SURVIVAL : GameType.SPECTATOR)
                : mode.getPlayerMode();
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void numen$renderTargetHands(ItemInHandRenderer renderer, float partialTick, PoseStack pose,
                                         MultiBufferSource.BufferSource buffers, LocalPlayer owner, int light,
                                         Operation<Void> original) {
        AbstractClientPlayer target = SpectatorClient.target();
        if (target == null) {
            original.call(renderer, partialTick, pose, buffers, owner, light);
        } else {
            int targetLight = Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(target, partialTick);
            ((SpectatorHandRenderer) renderer).numen$renderHands(partialTick, pose, buffers, target, targetLight);
        }
    }
}
