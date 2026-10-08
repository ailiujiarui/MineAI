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
import java.util.List;

/**
 * CI 门槛:{@code bench/metrics-thresholds.json} 里给五项老指标各定一个不许越过的界。成功率、pass^k 是下限(不得低于),
 * 命令出错率、轮数、墙钟是上限(不得高于);没给的那项跳过。{@link #violations} 返回越界的说明,空表表示全过。
 *
 * <p>三项新指标只报不卡:它们还没有稳定到能当门的程度(见 docs/bench.md)。要卡也在同一份 JSON 里加,这里不读。
 */
public record MetricThresholds(double successRateMin, double passK3Min, double commandErrorRateMax,
                               double turnsMax, double wallSecondsMax) {

    /** 没有配门槛的哨兵:跳过这项检查。 */
    private static final double UNSET = Double.NaN;

    public static MetricThresholds read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MetricThresholds parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new MetricThresholds(
                bound(root, "successRate", "min"), bound(root, "passK3", "min"),
                bound(root, "commandErrorRate", "max"), bound(root, "turns", "max"),
                bound(root, "wallSeconds", "max"));
    }

    /** 取 {@code name} 那一项的 {@code bound} 值;没给是 {@link #UNSET}。 */
    private static double bound(JsonObject root, String name, String bound) {
        JsonElement item = root.get(name);
        if (item == null || !item.isJsonObject()) {
            return UNSET;
        }
        JsonElement value = item.getAsJsonObject().get(bound);
        return value == null || value.isJsonNull() ? UNSET : value.getAsDouble();
    }

    /** 五项各自越界的说明;空表是全过。 */
    public List<String> violations(EvalMetrics.Aggregate m) {
        List<String> out = new ArrayList<>();
        below(out, "成功率", m.successRate(), successRateMin);
        below(out, "pass^k", m.passK3(), passK3Min);
        above(out, "命令出错率", m.commandErrorRate(), commandErrorRateMax);
        above(out, "轮数", m.turns(), turnsMax);
        above(out, "墙钟秒", m.wallSeconds(), wallSecondsMax);
        return out;
    }

    private static void below(List<String> out, String name, double value, double min) {
        if (!Double.isNaN(min) && !Double.isNaN(value) && value < min) {
            out.add(name + " " + show(value) + " 低于下限 " + show(min));
        }
    }

    private static void above(List<String> out, String name, double value, double max) {
        if (!Double.isNaN(max) && !Double.isNaN(value) && value > max) {
            out.add(name + " " + show(value) + " 高过上限 " + show(max));
        }
    }

    private static String show(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
