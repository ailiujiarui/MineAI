package com.dwinovo.numen.core.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 人工设施夹具可加速调用原生死亡动画tick;正式评测不调用此入口。 */
@Mixin(EnderDragon.class)
public interface DragonAnimationEvalAccessor {
    @Invoker("tickDeath") void numen$tickNativeDeath();
}
