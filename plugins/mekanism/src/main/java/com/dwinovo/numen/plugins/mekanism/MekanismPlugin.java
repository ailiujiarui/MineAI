package com.dwinovo.numen.plugins.mekanism;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Mekanism 联动(新模型):把 Mekanism 机器的<b>只读状态</b>以上游的 {@code NumenApi.api("machine", …)}
 * 一组原子函数交给脚本——朝向、各面每种传输的输入/输出、标准物品/流体/能量、化学罐、热量。
 *
 * <p>命名空间 {@code mekanism}:脚本里是 {@code mekanism.machine.inspect(x, y, z)}。旧模型里的
 * GUI 槽位处理器与 {@code adapters/mekanism.json} 的机器配方表随适配器机制一起移除,这里只保留
 * 服务端无界面也能读到的机器状态/配置。目标模组不在场时整个模块不会被 {@code Builtin} 放行,
 * 一个类都不加载。
 */
public final class MekanismPlugin {

    private MekanismPlugin() {}

    /**
     * 由 {@code Builtin} 在确认 Mekanism 在场后调用;{@code skillsRoot} 是插件 jar 内的
     * {@code plugins/mekanism/skills}(眼下不带技能,通常为 null)。
     */
    public static void install(Path skillsRoot) {
        NumenPlugins.register("mekanism", numen -> {
            MekanismApi.install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
