package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** 只抄原生插眼前后事实,不改交互、物资或权限裁决。 */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeEvalMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult numen$observeEyeInsertion(UseOnContext context, Operation<InteractionResult> original) {
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) return original.call(context);
        BlockState previous = level.getBlockState(context.getClickedPos());
        if (!previous.is(Blocks.END_PORTAL_FRAME)) return original.call(context);
        ItemStack before = context.getItemInHand().copy();
        WorldEvalObservers.itemUseOnStarted(level, context.getPlayer(), context.getClickedPos(), previous, before);
        InteractionResult result = original.call(context);
        WorldEvalObservers.itemUsedOn(level, context.getPlayer(), context.getClickedPos(), previous,
                before, context.getItemInHand().getCount());
        return result;
    }
}
