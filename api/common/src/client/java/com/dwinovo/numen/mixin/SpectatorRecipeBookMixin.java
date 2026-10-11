package com.dwinovo.numen.mixin;

import com.dwinovo.numen.spectator.SpectatorMenuClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla recipe-book init binds the LocalPlayer menu; a replica has neither that binding nor a recipe book. */
@Mixin(RecipeBookComponent.class)
public abstract class SpectatorRecipeBookMixin {
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void numen$keepRecipeBookDetached(int width, int height, Minecraft minecraft,
            boolean narrow, RecipeBookMenu<?, ?> menu, CallbackInfo ci) {
        if (SpectatorMenuClient.initializingRenderer()) ci.cancel();
    }
}
