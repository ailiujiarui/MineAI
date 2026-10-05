package com.dwinovo.numen.plugins.ae2;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * AE2 联动(新模型):把 ME 网络、机器配置、样板编码、自动合成四类能力,以上游的
 * {@code NumenApi.api("组", …)} 原子组交给脚本;高层流程写成随包发布的 Lua 模块,知识写进技能。
 *
 * <p>命名空间 {@code ae2}:脚本里是 {@code ae2.network.inspect(...)}、{@code ae2.config.read(...)} 这样。
 * 目标模组不在场时整个模块不会被 {@code Builtin} 放行,一个类都不加载。
 */
public final class Ae2Plugin {

    private Ae2Plugin() {}

    /** 由 {@code Builtin} 在确认 AE2 在场后调用;{@code skillsRoot} 是插件 jar 内的 {@code plugins/ae2/skills}。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register("ae2", numen -> {
            Ae2NetworkApi.install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
