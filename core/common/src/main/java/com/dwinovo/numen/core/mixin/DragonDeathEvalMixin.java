package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 致死记录来自 LivingEntity.die 的实际提交,不是血量采样或死亡阶段推断。 */
@Mixin(LivingEntity.class)
public abstract class DragonDeathEvalMixin {
    @Shadow protected boolean dead;

    @WrapMethod(method = "die")
    private void numen$observeNativeDragonDeath(DamageSource source, Operation<Void> original) {
        LivingEntity victim = (LivingEntity) (Object) this;
        boolean wasDead = dead;
        LivingEntity credit = victim.getKillCredit();
        original.call(source);
        if (victim instanceof EnderDragon && !victim.level().isClientSide && !wasDead && dead) {
            WorldEvalObservers.entityDied(victim, source, credit);
        }
    }
}
