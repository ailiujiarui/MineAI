package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Viewing never feeds world actions, owner hotbar selection or vanilla spectator target controls. */
@Mixin(Minecraft.class)
public abstract class SpectatorMinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void numen$viewKeys(CallbackInfo ci) {
        if (!SpectatorClient.active()) return;
        Minecraft mc = (Minecraft) (Object) this;
        while (mc.options.keyInventory.consumeClick()) SpectatorClient.openInventory();
        for (KeyMapping key : mc.options.keyHotbarSlots) while (key.consumeClick()) {}
        while (mc.options.keySwapOffhand.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keyPickItem.consumeClick()) {}
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void numen$noAttack(CallbackInfoReturnable<Boolean> cir) {
        if (SpectatorClient.active()) cir.setReturnValue(false);
    }

    @Inject(method = {"startUseItem", "pickBlock"}, at = @At("HEAD"), cancellable = true)
    private void numen$noUse(CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void numen$noHeldAttack(boolean attacking, CallbackInfo ci) {
        if (SpectatorClient.active()) ci.cancel();
    }
}
