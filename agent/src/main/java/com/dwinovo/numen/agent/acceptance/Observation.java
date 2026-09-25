package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 靶场的一次快照:几条探针各自的输出,来自世界,不来自模型。
 *
 * @param reachable 靶场连没连上;连不上时所有探针一律算不命中——判不了不等于做完了
 * @param outputs   探针 id → 原始输出
 */
public record Observation(long atMs, boolean reachable, String error, Map<String, String> outputs) {

    public static Observation of(long atMs, Map<String, String> outputs) {
        return new Observation(atMs, true, null, Map.copyOf(outputs));
    }

    public static Observation unreachable(long atMs, String error) {
        return new Observation(atMs, false, error == null ? "rcon unreachable" : error, Map.of());
    }

    public String output(String probeId) {
        return outputs.get(probeId);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("at", atMs);
        o.addProperty("reachable", reachable);
        if (error != null) {
            o.addProperty("error", error);
        }
        JsonObject outs = new JsonObject();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(outputs).entrySet()) {
            outs.addProperty(e.getKey(), e.getValue());
        }
        o.add("outputs", outs);
        return o;
    }
}
