package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.spectator.SpectatorMenuBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

/** 拿到手上的真实变化交给观看者;寻路与内容共用的原语仍原样执行并交回身体动作。 */
@Mixin(value = Hotbar.class, remap = false)
public abstract class SpectatorHotbarActionMixin {
    @WrapMethod(method = "hold")
    private static Optional<BodyAction> numen$showHold(ServerPlayer body, int slot,
            Operation<Optional<BodyAction>> original) {
        if (!(body instanceof NumenPlayer companion)) return original.call(body, slot);
        try (var display = SpectatorMenuBridge.inventoryAction(companion)) {
            return original.call(body, slot);
        }
    }

    @WrapMethod(method = "grip")
    private static Hotbar.Grip numen$showGrip(ServerPlayer body, Item item, Operation<Hotbar.Grip> original) {
        if (!(body instanceof NumenPlayer companion)) return original.call(body, item);
        try (var display = SpectatorMenuBridge.inventoryAction(companion)) {
            return original.call(body, item);
        }
    }
}
