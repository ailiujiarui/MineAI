package com.dwinovo.numen.agent.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 一次重载的结果——给 {@code /numen adapter reload}、{@code list} 和日志看。
 *
 * <p>{@link #skipped} 每一项都带<b>原因</b>(模组不在 / 关掉了 / schema 太新 / 依赖的处理器没登记 /
 * 这一侧暂不支持),这样"配置明明在却没生效"能当场回答,不用去翻日志。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record ReloadReport(long at, int loaded, int failed, List<Skipped> skipped,
                           List<String> added, List<String> removed, List<String> updated,
                           List<String> errors) {

    /** 被跳过的适配器与原因。 */
    public record Skipped(String id, String reason) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("reason", reason);
            return o;
        }
    }

    public ReloadReport {
        skipped = List.copyOf(skipped);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        updated = List.copyOf(updated);
        errors = List.copyOf(errors);
    }

    static ReloadReport of(long at, int loaded, int failed, List<Skipped> skipped, List<String> errors,
                           Map<String, AdapterSpec> previous, Map<String, AdapterSpec> next) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        for (String id : new TreeSet<>(next.keySet())) {
            AdapterSpec before = previous.get(id);
            if (before == null) {
                added.add(id);
            } else if (!before.equals(next.get(id))) {
                updated.add(id);
            }
        }
        for (String id : previous.keySet()) {
            if (!next.containsKey(id)) {
                removed.add(id);
            }
        }
        return new ReloadReport(at, loaded, failed, skipped, added, removed, updated, errors);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("at", at);
        o.addProperty("loaded", loaded);
        o.addProperty("failed", failed);
        JsonArray skip = new JsonArray();
        for (Skipped s : skipped) {
            skip.add(s.toJson());
        }
        o.add("skipped", skip);
        o.add("added", array(added));
        o.add("removed", array(removed));
        o.add("updated", array(updated));
        o.add("errors", array(errors));
        return o;
    }

    private static JsonArray array(List<String> values) {
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        return arr;
    }
}
