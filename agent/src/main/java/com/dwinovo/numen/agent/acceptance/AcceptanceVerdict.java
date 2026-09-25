package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 一次目标判定:世界达成了没有、为什么、证据是哪几条探针。
 *
 * @param met    判据成立
 * @param reason 人读的一句话。成立时是命中的证据,不成立时还差什么
 * @param probes 这次量测的逐条证据
 */
public record AcceptanceVerdict(boolean met, String reason, List<ProbeResult> probes) {

    public static AcceptanceVerdict met(String reason, List<ProbeResult> probes) {
        return new AcceptanceVerdict(true, reason, probes);
    }

    public static AcceptanceVerdict unmet(String reason, List<ProbeResult> probes) {
        return new AcceptanceVerdict(false, reason, probes);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("met", met);
        o.addProperty("reason", reason == null ? "" : reason);
        JsonArray arr = new JsonArray();
        for (ProbeResult p : probes) {
            arr.add(p.toJson());
        }
        o.add("probes", arr);
        return o;
    }
}
