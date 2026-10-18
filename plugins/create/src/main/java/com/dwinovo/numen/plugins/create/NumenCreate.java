package com.dwinovo.numen.plugins.create;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * 机械动力联动:她看得懂动力、传动与产线。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。机械动力在不在由 {@code Builtin}
 * 那道闸判断,判断为真才调 {@link #install}——它不在的话,这个类<b>一次都不会被加载</b>。
 *
 * <p>目前带的是知识:转速与应力、传动怎么连、扳手与工程师护目镜、加工机器、物流、可动结构、
 * 蓝图大炮(见本模块 {@code plugins/create/skills/create/SKILL.md})。她那套通用能力到不了的
 * 模组机制(例如整张动力网的应力读数),之后按需补成 {@code create} 名字空间下的函数。
 */
public final class NumenCreate {

    /** 这个联动在她的 API 里的名字空间:{@code create.*}。 */
    static final String NAMESPACE = "create";

    private NumenCreate() {}

    /** 由 {@code Builtin} 在确认机械动力在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(NAMESPACE, numen -> {
            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
