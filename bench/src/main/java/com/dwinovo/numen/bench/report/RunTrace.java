package com.dwinovo.numen.bench.report;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行的记录文件({@code transcripts/*.jsonl})读成 {@link TraceEvent} 列表:一行一件事,一行一个 JSON 对象。
 * 只把 {@code kind} 与 {@code ms} 抽成字段,其余键值原样收进 {@link TraceEvent#fields()}。读不成 JSON 的行跳过——旧记录里
 * 可能有别的写法;缺记录文件给空表,新指标在那次运行上没有样本。
 */
public final class RunTrace {

    private RunTrace() {}

    /** 读一份记录;没有这个文件给空表。 */
    public static List<TraceEvent> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一行一事件地读。 */
    public static List<TraceEvent> parse(List<String> lines) {
        List<TraceEvent> out = new ArrayList<>();
        for (String line : lines) {
            TraceEvent event = parseLine(line);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }

    private static TraceEvent parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                return null;
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!object.has("kind")) {
            return null;
        }
        String kind = object.get("kind").getAsString();
        long ms = object.has("ms") && object.get("ms").isJsonPrimitive() ? object.get("ms").getAsLong() : 0L;
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            if (e.getKey().equals("kind") || e.getKey().equals("ms")) {
                continue;
            }
            JsonElement value = e.getValue();
            fields.put(e.getKey(), value == null || value.isJsonNull() ? "" : value.getAsString());
        }
        return new TraceEvent(kind, ms, fields);
    }
}
