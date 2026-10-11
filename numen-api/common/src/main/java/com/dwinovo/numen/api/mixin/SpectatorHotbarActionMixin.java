package com.dwinovo.numen.api.mixin;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.spectator.SpectatorMenuBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

/** 拿到手上的真实变化交给观看者;寻路与内容共用的原语仍原样执行并交回身体动作。 */
@Mixin(value = Hotbar.class, remap = false)
public abstract class SpectatorHotbarActionMixin {

    @Shadow
    @Final
    private ServerPlayer body;

    @WrapMethod(method = "hold")
    private Optional<BodyAction> numen$showHold(int slot, Operation<Optional<BodyAction>> original) {
        if (!(body instanceof NumenPlayer companion)) return original.call(slot);
        try (var display = SpectatorMenuBridge.inventoryAction(companion)) {
            return original.call(slot);
        }
    }

    @WrapMethod(method = "grip")
    private Hotbar.Grip numen$showGrip(Item item, Operation<Hotbar.Grip> original) {
        if (!(body instanceof NumenPlayer companion)) return original.call(item);
        try (var display = SpectatorMenuBridge.inventoryAction(companion)) {
            return original.call(item);
        }
    }
}
