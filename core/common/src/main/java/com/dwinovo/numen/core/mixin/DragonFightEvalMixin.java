package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.eval.WorldEvalObservers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;

/** 原生战斗提交必须与致死、归属、统计独立一致;指令 kill 也会调用此处,不能单独判成功。 */
@Mixin(EndDragonFight.class)
public abstract class DragonFightEvalMixin {
    @WrapMethod(method = "setDragonKilled")
    private void numen$observeDragonFightEnd(EnderDragon dragon, Operation<Void> original) {
        EndDragonFight fight = (EndDragonFight) (Object) this;
        boolean wasKilled = fight.saveData().dragonKilled();
        original.call(dragon);
        if (!wasKilled && fight.saveData().dragonKilled()) WorldEvalObservers.dragonFightEnded(fight, dragon);
    }
}
