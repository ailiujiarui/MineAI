package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * JEI 联动(新模型):把"一个物品在 JEI 里的配方"登记成 {@code jei.recipe} 一组 API,
 * 由脚本在主人的客户端上当场问。旧模型的 {@code jei_recipe} 工具没有技能与模块,这里照旧不带。
 *
 * <p>JEI 的配方注册表只在客户端有,所以这组函数是 {@link com.dwinovo.numen.sdk.ClientCall} 一侧的:
 * 服务端上的程序调它,引擎转发到主人的客户端执行,再把结果送回来。目标模组不在场时整个模块不会被
 * {@code Builtin} 放行,一个类都不加载。
 */
public final class JeiPlugin {

    private JeiPlugin() {}

    /** 由 {@code Builtin} 在确认 JEI 在场后调用;本联动不带技能,{@code skillsRoot} 忽略。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register("jei", numen -> JeiApi.install(numen));
    }

    /** 不带技能目录的接线:闸门用两参形式,省掉"技能目录不在 jar 里"的那条警告。 */
    public static void install() {
        install(null);
    }
}
