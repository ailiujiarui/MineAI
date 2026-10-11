package com.dwinovo.numen.mixin;

import com.dwinovo.numen.spectator.ServerSpectatorSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class SpectatorPlayerListMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void numen$restoreBeforeLogoutSave(ServerPlayer player, CallbackInfo ci) {
        ServerSpectatorSessions.exit(player);
    }
}
