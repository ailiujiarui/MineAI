package com.dwinovo.numen.mixin;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** 原版 handlePortal 的调用点是路线证据;命令和直接 changeDimension 不经过这里。 */
@Mixin(Entity.class)
public abstract class NativePortalEvalMixin {
    @Shadow public PortalProcessor portalProcess;

    @WrapOperation(method = "handlePortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;"))
    private Entity numen$observeNativePortalTravel(Entity entity, DimensionTransition transition, Operation<Entity> original) {
        boolean watched = entity instanceof NumenPlayer body && WorldEvalObservers.isActive(body)
                && portalProcess != null && portalProcess.isSamePortal((EndPortalBlock) Blocks.END_PORTAL);
        ServerLevel source = watched ? (ServerLevel) entity.level() : null;
        BlockPos entry = watched ? portalProcess.getEntryPosition().immutable() : null;
        Entity arrived = original.call(entity, transition);
        if (watched) WorldEvalObservers.portalTravelled((NumenPlayer) entity, source, entry, arrived);
        return arrived;
    }
}
