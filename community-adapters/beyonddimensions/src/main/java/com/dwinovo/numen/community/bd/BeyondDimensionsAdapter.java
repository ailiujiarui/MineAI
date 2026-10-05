package com.dwinovo.numen.community.bd;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Beyond Dimensions 的社区适配器(独立 mod)。
 *
 * <p>只做一件事:把 BD 终端的虚拟存储从服务端读出来交给模型。BD 的网络物品不在原版菜单槽里
 * (服务端槽位索引固定为 -1,内容在客户端渲染),所以 {@code use gui} 对 BD 终端永远是一整片空槽。
 * 本 mod 登记一个名为 {@code bd_storage} 的 GUI 处理器,把一个菜单 id 路由到这个处理器(见 jar 内
 * {@code /adapters/beyonddimensions.json}),读的是菜单上的 {@code DimensionsNetMenu.storage}——
 * 服务端权威的那份 {@code IStackHandler}。
 *
 * <p>它不经 {@code NumenPlugins} 以外的任何入口,不引用 core,不装 Mixin;BD 不在场时整条 mod 由
 * 加载器依赖判定挡在门外。写世界/搬运不在本适配器范围内,只有读。
 */
@Mod(BeyondDimensionsAdapter.MOD_ID)
public final class BeyondDimensionsAdapter {

    public static final String MOD_ID = "numen_bd_adapter";

    public BeyondDimensionsAdapter(IEventBus eventBus, ModContainer container) {
        NumenPlugins.register(numen -> {
            numen.registerGuiHandler("bd_storage", BdStorageRead::read);
            Path root = ModList.get().getModFileById(MOD_ID).getFile().findResource("adapters");
            if (Files.exists(root)) {
                numen.bundleAdapters(root);
            }
        });
    }
}
