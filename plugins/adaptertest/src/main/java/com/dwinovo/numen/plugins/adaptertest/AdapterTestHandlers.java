package com.dwinovo.numen.plugins.adaptertest;

import com.dwinovo.numen.api.NumenPlugins;
import com.google.gson.JsonObject;

/**
 * 独立的测试插件:只对着瘦 api jar 编译,实现并注册一个 use 处理器与一个 gui 处理器。
 *
 * <p>它存在的意义有二:一是证明第三方模组**看得见并实现得了**适配器 SPI(修掉「SPI 不在瘦 jar」
 * 那条根因);二是证明注册门({@code NumenApi.registerUseHandler} / {@code registerGuiHandler})可用。
 *
 * <p>本模块**不被核心内嵌**(core 的 build.gradle 只列了那几个正式联动),所以只进构建、不进成品。
 */
public final class AdapterTestHandlers {

    private AdapterTestHandlers() {}

    /** 登记两个处理器。真实联动会在自己模组的初始化里调它。 */
    public static void install() {
        NumenPlugins.register(numen -> {
            // use 意图:真实实现会在这里调目标模组的公开 API(如 TaCZ 的 IGunOperator.shoot())。
            numen.registerUseHandler("tacz_fire", (body, itemId) -> true);
            // gui 来源:真实实现会读目标模组自己的菜单数据(如 BD 的客户端渲染列表)。
            numen.registerGuiHandler("bd_storage", (body, menu, source) -> new JsonObject());
        });
    }
}
