package com.dwinovo.numen.bench.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 指标门(CI 用):把一份结果({@code runs.jsonl} 与它的 {@code transcripts/})聚合成指标,与
 * {@code bench/metrics-thresholds.json} 里的门槛比;五项老指标有一项越界就以非零退出码失败,让 CI 拦住回归。
 *
 * <pre>
 * java ... com.dwinovo.numen.bench.report.MetricGate [结果目录] [门槛文件]
 * </pre>
 *
 * <p>默认读 {@code bench/fixtures/sample} 与 {@code bench/metrics-thresholds.json}:仓库里带的一份小夹具,
 * 不联网、不调模型,所以能在 CI 上跑。真的结果目录用 {@code ./gradlew :bench:metricGate -Presults=core/neoforge/runs/bench/results/<时间戳>}。
 */
public final class MetricGate {

    /** 夹具与门槛的默认位置(相对仓库根)。 */
    public static final String DEFAULT_RESULTS = "bench/fixtures/sample";
    public static final String DEFAULT_THRESHOLDS = "bench/metrics-thresholds.json";

    private MetricGate() {}

    public static void main(String[] args) {
        Path results = Path.of(args.length > 0 ? args[0] : DEFAULT_RESULTS);
        Path thresholds = Path.of(args.length > 1 ? args[1] : DEFAULT_THRESHOLDS);
        List<Run> runs = Runs.read(results);
        EvalMetrics.Aggregate metrics = EvalMetrics.aggregate(runs, traces(results, runs));
        MetricThresholds limits = MetricThresholds.read(thresholds);
        List<String> violations = limits.violations(metrics);
        System.out.print(report(results, metrics, limits, violations));
        if (!violations.isEmpty()) {
            System.err.println("指标门没过:" + String.join(";", violations));
            System.exit(1);
        }
    }

    /** 每次运行的记录文件路径({@link Run#transcript()},相对结果目录)到原始事件。 */
    static java.util.Map<String, List<TraceEvent>> traces(Path results, List<Run> runs) {
        java.util.Map<String, List<TraceEvent>> out = new java.util.LinkedHashMap<>();
        for (Run run : runs) {
            String relative = run.transcript();
            if (relative == null || relative.isBlank() || out.containsKey(relative)) {
                continue;
            }
            out.put(relative, RunTrace.read(results.resolve(relative)));
        }
        return out;
    }

    /** 给人看的一张表:五项老指标带门槛与判定,三项新指标只报。 */
    public static String report(Path results, EvalMetrics.Aggregate m, MetricThresholds t, List<String> violations) {
        StringBuilder md = new StringBuilder();
        md.append("# 指标门\n\n");
        md.append("- 结果:").append(results).append('\n');
        md.append("- 真实模型运行:").append(m.liveRuns()).append(" 次,通过 ").append(m.successes()).append(" 次\n");
        md.append("- 命令出错率 = 工具失败数 ÷ 工具调用数;轮数、墙钟是每次运行的均值\n\n");
        md.append("| 门指标 | 值 | 门槛 | 判定 |\n|---|---|---|---|\n");
        md.append(row("成功率", pct(m.successRate()), min(t.successRateMin()),
                below(m.successRate(), t.successRateMin())));
        md.append(row("pass^" + Summary.K, num(m.passK3()), min(t.passK3Min()),
                below(m.passK3(), t.passK3Min())));
        md.append(row("命令出错率", pct(m.commandErrorRate()), max(t.commandErrorRateMax()),
                above(m.commandErrorRate(), t.commandErrorRateMax())));
        md.append(row("轮数", num(m.turns()), max(t.turnsMax()), above(m.turns(), t.turnsMax())));
        md.append(row("墙钟秒", num(m.wallSeconds()), max(t.wallSecondsMax()),
                above(m.wallSeconds(), t.wallSecondsMax())));
        md.append("\n## 新指标(只报,不卡)\n\n");
        md.append("| 指标 | 值 |\n|---|---|\n");
        md.append(info("打断恢复率", pct(m.interruptionRecoveryRate())));
        md.append(info("重定方向扫描次数(均)", num(m.scansToReorient())));
        md.append(info("到有效动作的轮数(均)", num(m.turnsToEffective())));
        md.append(info("征询次数 / 拒绝 / 悬而未决",
                m.consentPrompts() + " / " + m.consentDenied() + " / " + m.consentTimedOut()));
        md.append(info("因征询被放弃的任务占比", pct(m.consentAbandonedFraction())));
        md.append(info("自我纠正率", pct(m.selfCorrectionRate())));
        md.append('\n');
        md.append(violations.isEmpty() ? "全部通过。\n" : "未通过:" + String.join(";", violations) + "\n");
        return md.toString();
    }

    private static String row(String name, String value, String limit, boolean failed) {
        return "| " + name + " | " + value + " | " + limit + " | " + (failed ? "未过" : "过") + " |\n";
    }

    private static boolean below(double value, double min) {
        return !Double.isNaN(min) && !Double.isNaN(value) && value < min;
    }

    private static boolean above(double value, double max) {
        return !Double.isNaN(max) && !Double.isNaN(value) && value > max;
    }

    private static String info(String name, String value) {
        return "| " + name + " | " + value + " |\n";
    }

    private static String min(double v) {
        return Double.isNaN(v) ? "—" : "≥ " + trim(v);
    }

    private static String max(double v) {
        return Double.isNaN(v) ? "—" : "< " + trim(v);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v * 100) + "%";
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "—" : trim(v);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
