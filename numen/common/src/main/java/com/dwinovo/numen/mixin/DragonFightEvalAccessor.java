package com.dwinovo.numen.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 人工校准夹具调用原生龙战创建入口,与正式等待天然初始化的路径分开。 */
@Mixin(EndDragonFight.class)
public interface DragonFightEvalAccessor {
    @Invoker("createNewDragon") EnderDragon numen$createNativeDragon();
}
