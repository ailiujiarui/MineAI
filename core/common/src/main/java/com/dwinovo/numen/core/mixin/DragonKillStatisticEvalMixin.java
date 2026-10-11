package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** 读取原生 Player.killedEntity 前后统计,不补发统计也不改变伤害来源。 */
@Mixin(Player.class)
public abstract class DragonKillStatisticEvalMixin {
    @WrapMethod(method = "killedEntity")
    private boolean numen$observeDragonStatistic(ServerLevel level, LivingEntity victim, Operation<Boolean> original) {
        Player actor = (Player) (Object) this;
        if (!(victim instanceof EnderDragon) || !(actor instanceof ServerPlayer serverActor)) return original.call(level, victim);
        var stat = Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON);
        int before = serverActor.getStats().getValue(stat);
        boolean result = original.call(level, victim);
        WorldEvalObservers.killStatistic(actor, victim, before, serverActor.getStats().getValue(stat));
        return result;
    }
}
