package com.dwinovo.numen.plugins.twilightforest;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * 暮色森林联动:她进得了门、看得懂这个世界。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。暮色森林在不在由 {@code Builtin}
 * 那道闸判断,判断为真才调 {@link #install}——它不在的话,这个类<b>一次都不会被加载</b>。
 *
 * <p>目前带的是知识:传送门怎么开、地牢套件的顺序、每段路上的危险(见本模块
 * {@code plugins/twilightforest/skills/twilight_forest/SKILL.md})。她那套通用能力到不了的模组
 * 机制(例如魔法地图上画着的东西),之后按需补成 {@code twilightforest} 名字空间下的函数。
 */
public final class NumenTwilightForest {

    /** 这个联动在她的 API 里的名字空间:{@code twilightforest.*}。 */
    static final String NAMESPACE = "twilightforest";

    private NumenTwilightForest() {}

    /** 由 {@code Builtin} 在确认暮色森林在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(NAMESPACE, numen -> {
            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
