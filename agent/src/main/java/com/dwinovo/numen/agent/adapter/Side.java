package com.dwinovo.numen.agent.adapter;

import java.util.Locale;

/**
 * 适配器跑在哪一侧。
 *
 * <p>Numen 是"大脑在主人客户端、身体在服务端"。有些适配(读客户端渲染的数据)只在客户端有意义,
 * 有些(改服务端容器)只在服务端。适配器必须自己声明,免得在错的一侧白跑。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public enum Side {
    CLIENT,
    SERVER,
    BOTH;

    public static Side from(String value) {
        if (value == null) {
            return SERVER;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "client" -> CLIENT;
            case "both" -> BOTH;
            default -> SERVER;
        };
    }

    public boolean runsOn(Side other) {
        return this == BOTH || other == BOTH || this == other;
    }
}
