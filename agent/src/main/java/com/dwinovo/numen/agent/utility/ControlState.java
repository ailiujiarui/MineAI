package com.dwinovo.numen.agent.utility;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 仲裁层看到的现场:一堆数值信号 + 这一 tick 有哪些事件。
 *
 * <p>它是纯数据,不含 Minecraft、不含网络——打分函数只认它,于是 Utility 层可以脱离服务端
 * 单测。JEV 的判断放进来也只落成一个普通信号({@code jev.*}),仲裁器不需要知道它从哪来。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record ControlState(long nowMs, Map<String, Double> signals, Set<String> events) {

    public ControlState {
        signals = Map.copyOf(signals);
        events = Set.copyOf(events);
    }

    public static ControlState of(long nowMs) {
        return new ControlState(nowMs, Map.of(), Set.of());
    }

    public ControlState with(String key, double value) {
        Map<String, Double> next = new TreeMap<>(signals);
        next.put(key, value);
        return new ControlState(nowMs, next, events);
    }

    public ControlState withEvent(String kind) {
        Set<String> next = new TreeSet<>(events);
        next.add(kind);
        return new ControlState(nowMs, signals, next);
    }

    public double signal(String key) {
        return signals.getOrDefault(key, 0.0D);
    }

    public boolean hasSignal(String key) {
        return signals.containsKey(key);
    }

    public boolean hasEvent(String kind) {
        return events.contains(kind);
    }

    /** 拼给 JEV / 边界 LLM 看的一段现场。 */
    public String render() {
        StringBuilder sb = new StringBuilder("signals");
        for (Map.Entry<String, Double> e : new TreeMap<>(signals).entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        if (!events.isEmpty()) {
            sb.append(" events");
            for (String e : new TreeSet<>(events)) {
                sb.append(' ').append(e);
            }
        }
        return sb.toString();
    }
}
