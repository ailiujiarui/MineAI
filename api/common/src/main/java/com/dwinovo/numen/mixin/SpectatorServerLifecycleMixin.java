package com.dwinovo.numen.mixin;

import com.dwinovo.numen.spectator.ServerSpectatorSessions;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class SpectatorServerLifecycleMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void numen$restoreBeforeShutdownSave(CallbackInfo ci) {
        ServerSpectatorSessions.restoreAll((MinecraftServer) (Object) this);
    }
}
