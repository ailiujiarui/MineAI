package com.dwinovo.numen.agent.utility;

import java.util.function.Function;

/**
 * 一条"考虑"(consideration):把一个现场事实折算成 {@code [0,1]} 的价值,再乘权重。
 *
 * <p>打分函数是<b>纯的、可测的</b>——这正是把 Utility 留在快层、不联网的理由。语义判断
 * (该逃命还是把这块矿挖完)那类事交给 JEV,由宿主把它落成信号再喂进来。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param id     信号名,日志与报告里认它
 * @param weight 权重
 * @param value  现场 → [0,1];越界会被夹紧,NaN 当 0
 */
public record Consideration(String id, double weight, Function<ControlState, Double> value) {

    public static Consideration of(String id, double weight, Function<ControlState, Double> value) {
        return new Consideration(id, weight, value);
    }

    public double score(ControlState state) {
        double v = value.apply(state);
        if (Double.isNaN(v)) {
            v = 0.0D;
        }
        v = Math.max(0.0D, Math.min(1.0D, v));
        return v * weight;
    }
}
