package com.dwinovo.numen.plugins.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 插件本体:JEI 在客户端就绪后回调 {@link #onRuntimeAvailable(IJeiRuntime)},这里抓住运行期,
 * 交给 {@link JeiApi} 的客户端函数用。它<b>只由 JEI 加载</b>(装了 JEI 的客户端),core 不引用它。
 */
@JeiPlugin
public final class JeiRuntimeBridge implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("numen", "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiApi.bind(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiApi.bind(null);
    }
}
