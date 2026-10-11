package com.dwinovo.numen.mixin;

import com.dwinovo.numen.spectator.ServerSpectatorSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 观看包没有操作转发路径,原版操作包也不能改主人背包或共享容器。 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpectatorInputMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handleContainerClick", "handleContainerButtonClick", "handlePlaceRecipe",
            "handleSetCreativeModeSlot", "handleSetCarriedItem", "handlePlayerAction", "handleUseItem",
            "handleUseItemOn", "handleInteract", "handlePickItem", "handlePlayerCommand",
            "handleTeleportToEntityPacket", "handlePlayerInput", "handleMoveVehicle", "handlePaddleBoat",
            "handleAnimate", "handlePlayerAbilities", "handleMovePlayer", "handleContainerClose",
            "handleRenameItem", "handleSetBeaconPacket", "handleSelectTrade", "handleEditBook",
            "handleContainerSlotStateChanged"}, at = @At("HEAD"), cancellable = true)
    private void numen$readOnlyViewing(CallbackInfo ci) {
        if (ServerSpectatorSessions.isViewing(player)) ci.cancel();
    }
}
