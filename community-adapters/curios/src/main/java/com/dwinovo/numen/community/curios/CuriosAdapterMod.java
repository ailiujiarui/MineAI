package com.dwinovo.numen.community.curios;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Curios 的社区适配器(独立 mod),走<b>数据适配器</b>那条路。
 *
 * <p>它登记一个名为 {@code curios} 的装备来源处理器,再由 jar 内 {@code /adapters/curios.json}
 * 把"所有物品"路由到这个容器名——{@code AdapterGearSource} 于是能枚举真实饰品栏、把
 * {@code curios:ring} 这类槽名交给模型。这与内嵌的 {@code plugins/curios}(强类型 {@code registerGear})
 * 做的是同一件事,只是换了社区能自己发的那条路。
 *
 * <p><b>不要与内嵌的 Curios 联动同时装</b>:两者都会向身体提供饰品槽,同时生效会看到重复的
 * {@code curios:*} 槽。它面向的是想自行维护 Curios 兼容、或内嵌联动未覆盖其 Curios 版本的整合包。
 *
 * <p>不经 data 搬运之外的任何入口,不引用 core,不装 Mixin。Curios 不在场时由加载器依赖挡下。
 */
@Mod(CuriosAdapterMod.MOD_ID)
public final class CuriosAdapterMod {

    public static final String MOD_ID = "numen_curios_adapter";

    public CuriosAdapterMod(IEventBus eventBus, ModContainer container) {
        NumenPlugins.register(numen -> {
            numen.registerGearHandler("curios", new CuriosGearHandler());
            Path root = ModList.get().getModFileById(MOD_ID).getFile().findResource("adapters");
            if (Files.exists(root)) {
                numen.bundleAdapters(root);
            }
        });
    }
}
