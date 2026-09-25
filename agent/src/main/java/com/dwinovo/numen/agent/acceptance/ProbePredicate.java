package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用探针谓词:条件由一条或几条 RCON 量测表达。{@link Mode#ALL} 要求每条都命中,
 * {@link Mode#ANY} 命中任意一条即可。
 *
 * <p><b>空谓词永不成立。</b>没有探针就没有判据,宁可判"没达成"再跑一轮,也不把没验过的
 * 活儿当成做完了。连不上靶场同样永不成立。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param condition 人读的条件正文
 * @param mode      多条探针怎么合并
 * @param probes    世界量测
 */
public record ProbePredicate(String condition, Mode mode, List<Probe> probes) implements GoalPredicate {

    public enum Mode {
        ALL,
        ANY
    }

    public ProbePredicate {
        probes = List.copyOf(probes);
    }

    public static ProbePredicate all(String condition, List<Probe> probes) {
        return new ProbePredicate(condition, Mode.ALL, probes);
    }

    public static ProbePredicate any(String condition, List<Probe> probes) {
        return new ProbePredicate(condition, Mode.ANY, probes);
    }

    @Override
    public AcceptanceVerdict evaluate(Observation obs) {
        List<ProbeResult> results = new ArrayList<>(probes.size());
        for (Probe p : probes) {
            String output = obs.output(p.id());
            boolean matched = obs.reachable() && p.expectation().matches(output);
            results.add(new ProbeResult(p.id(), p.command(), output, matched));
        }
        boolean met;
        if (probes.isEmpty()) {
            met = false;
        } else if (mode == Mode.ANY) {
            met = results.stream().anyMatch(ProbeResult::matched);
        } else {
            met = results.stream().allMatch(ProbeResult::matched);
        }
        if (met) {
            return AcceptanceVerdict.met(condition, results);
        }
        return AcceptanceVerdict.unmet(unmetReason(obs, results), results);
    }

    private String unmetReason(Observation obs, List<ProbeResult> results) {
        if (!obs.reachable()) {
            return condition + " —— 靶场连不上,判不出来:" + obs.error();
        }
        StringBuilder sb = new StringBuilder(condition).append(" —— 还差:");
        boolean first = true;
        for (int i = 0; i < results.size(); i++) {
            ProbeResult r = results.get(i);
            if (!r.matched()) {
                if (!first) {
                    sb.append(';');
                }
                first = false;
                sb.append(' ').append(r.id()).append(' ').append(probes.get(i).expectation().describe());
            }
        }
        return sb.toString();
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "probes");
        o.addProperty("condition", condition);
        o.addProperty("mode", mode.name().toLowerCase());
        JsonArray arr = new JsonArray();
        for (Probe p : probes) {
            arr.add(p.toJson());
        }
        o.add("probes", arr);
        return o;
    }

    public static ProbePredicate fromJson(JsonObject o) {
        String condition = o.has("condition") ? o.get("condition").getAsString() : "";
        Mode mode = "any".equalsIgnoreCase(o.has("mode") ? o.get("mode").getAsString() : "all")
                ? Mode.ANY : Mode.ALL;
        List<Probe> probes = new ArrayList<>();
        if (o.has("probes") && o.get("probes").isJsonArray()) {
            for (var el : o.getAsJsonArray("probes")) {
                probes.add(Probe.fromJson(el.getAsJsonObject()));
            }
        }
        return new ProbePredicate(condition, mode, probes);
    }
}
