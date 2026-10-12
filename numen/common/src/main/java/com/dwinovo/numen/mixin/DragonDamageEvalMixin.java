package com.dwinovo.numen.mixin;

import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 只观察真正扣血的原生边界;水晶原始来源保留,不能把原版最近玩家回退当本人动作。 */
@Mixin(EnderDragon.class)
public abstract class DragonDamageEvalMixin {
    @Unique private static final AtomicLong numen$damageSequence = new AtomicLong();
    @Unique private DamageSource numen$crystalSource;

    @WrapMethod(method = "onCrystalDestroyed")
    private void numen$observeCrystalOrigin(EndCrystal crystal, BlockPos pos, DamageSource source, Operation<Void> original) {
        DamageSource previous = numen$crystalSource;
        numen$crystalSource = source;
        try { original.call(crystal, pos, source); }
        finally { numen$crystalSource = previous; }
    }

    @WrapMethod(method = "reallyHurt")
    private boolean numen$observeActualDragonDamage(DamageSource source, float amount, Operation<Boolean> original) {
        EnderDragon dragon = (EnderDragon) (Object) this;
        float before = dragon.getHealth();
        long eventId = numen$damageSequence.incrementAndGet();
        boolean accepted = original.call(source, amount);
        if (!dragon.level().isClientSide) {
            Entity actor = numen$crystalSource == null ? source.getEntity() : numen$crystalSource.getEntity();
            String origin = numen$crystalSource == null ? "native_damage_source"
                    : actor instanceof Player ? "crystal_explicit_player" : "crystal_nearest_player_fallback";
            WorldEvalObservers.entityDamaged(dragon, source, before, dragon.getHealth(), accepted, origin, actor, eventId);
        }
        return accepted;
    }
}
