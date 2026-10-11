package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;

/** 击杀归属与 ENTITY_KILLED 是两个不同原生边界,分别留证。 */
@Mixin(ServerPlayer.class)
public abstract class DragonKillCreditEvalMixin {
    @WrapMethod(method = "awardKillScore")
    private void numen$observeDragonKillCredit(Entity victim, int score, DamageSource source, Operation<Void> original) {
        original.call(victim, score, source);
        if (victim instanceof EnderDragon) {
            WorldEvalObservers.killCredit((ServerPlayer) (Object) this, victim, source);
        }
    }
}
