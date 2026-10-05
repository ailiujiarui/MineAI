package com.dwinovo.numen.bench.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标聚合器:{@code fixtures/sample} 这份夹具的八项数字(五项老 + 三项新)、门槛判定,以及三项新指标各自的算法。
 * 样本从提交在仓库里的夹具读,和 docs/bench.md、CI 看到的同一份。
 */
class EvalMetricsTest {

    private static final Path SAMPLE = Path.of("fixtures/sample");
    private static final double EPS = 1e-9;

    /** 夹具的八项数字一次算清:老指标同 Summary 的口径,新指标从记录事件读。 */
    @Test
    void aggregatesTheCommittedSample() {
        List<Run> runs = Runs.read(SAMPLE);
        EvalMetrics.Aggregate m = EvalMetrics.aggregate(runs, MetricGate.traces(SAMPLE, runs));

        assertEquals(6, m.liveRuns());
        assertEquals(4, m.successes());
        assertEquals(4.0 / 6, m.successRate(), EPS);
        assertEquals(2, m.scenarios());
        assertEquals(0.5, m.passK3(), EPS, "mine_iron 3/3 是 1,craft 1/3 是 0,均值 0.5");
        assertEquals(7.0 / 32, m.commandErrorRate(), EPS);
        assertEquals(49.0 / 6, m.turns(), EPS);
        assertEquals(60.5, m.wallSeconds(), EPS);

        assertEquals(1, m.interruptions());
        assertEquals(1, m.interruptionsRecovered());
        assertEquals(1.0, m.interruptionRecoveryRate(), EPS);
        assertEquals(1.0, m.scansToReorient(), EPS);
        assertEquals(2.0, m.turnsToEffective(), EPS);

        assertEquals(2, m.consentPrompts());
        assertEquals(1, m.consentDenied());
        assertEquals(1, m.consentTimedOut());
        assertEquals(2, m.consentAbandonedTasks());
        assertEquals(2.0 / 6, m.consentAbandonedFraction(), EPS);

        assertEquals(3, m.commandErrors());
        assertEquals(2, m.selfCorrections());
        assertEquals(2.0 / 3, m.selfCorrectionRate(), EPS);
    }

    /** 夹具过得了配套的门槛;门槛提高后同一份数字被拦下,并说清哪一项越界。 */
    @Test
    void theSamplePassesItsThresholdsAndARegressionIsCaught() {
        List<Run> runs = Runs.read(SAMPLE);
        EvalMetrics.Aggregate m = EvalMetrics.aggregate(runs, MetricGate.traces(SAMPLE, runs));
        MetricThresholds limits = MetricThresholds.read(Path.of("metrics-thresholds.json"));
        assertTrue(limits.violations(m).isEmpty(), limits.violations(m).toString());

        MetricThresholds stricter = MetricThresholds.parse(
                "{\"successRate\":{\"min\":0.9},\"commandErrorRate\":{\"max\":0.01}}");
        List<String> violations = stricter.violations(m);
        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("成功率"), violations.toString());
        assertTrue(violations.get(1).contains("命令出错率"), violations.toString());
    }

    /** 打断恢复:停下后先扫两次定方向、再成功做一件事,记两次扫描、隔两条轮次边界。 */
    @Test
    void interruptionRecoveryCountsScansAndTurns() {
        List<TraceEvent> trace = List.of(
                turn(1), toolCall("a"), programEnd("a", "stopped"),
                turn(2), toolCall("b"), api("b", "numen.scan.blocks", ""),
                turn(3), toolCall("c"), api("c", "numen.scan.entities", ""),
                turn(4), toolCall("d"), api("d", "numen.work.dig", ""));
        EvalMetrics.RunMetrics m = EvalMetrics.RunMetrics.of(trace);
        assertEquals(1, m.interruptions());
        assertEquals(1, m.recovered());
        assertEquals(2, m.scans());
        assertEquals(3, m.turnsToEffective());
    }

    /** 停下后再没做出有效动作:记一次打断、没恢复,均值里没有它的样本。 */
    @Test
    void anUnrecoveredInterruptionCountsButAddsNoAverage() {
        List<TraceEvent> trace = List.of(
                turn(1), toolCall("a"), programEnd("a", "stopped"),
                turn(2), toolCall("b"), api("b", "numen.scan.blocks", ""));
        EvalMetrics.RunMetrics m = EvalMetrics.RunMetrics.of(trace);
        assertEquals(1, m.interruptions());
        assertEquals(0, m.recovered());
    }

    /** 自我纠正只看后面还有轮次的命令错;停在最后一轮的错不进分母。 */
    @Test
    void selfCorrectionSkipsTheLastTurn() {
        List<TraceEvent> trace = List.of(
                turn(1), toolCall("a"), api("a", "numen.inv.make", "bad_argument"), commandError("a"),
                turn(2), toolCall("b"), api("b", "numen.inv.make", ""));
        EvalMetrics.RunMetrics m = EvalMetrics.RunMetrics.of(trace);
        assertEquals(1, m.commandErrors());
        assertEquals(1, m.corrections());

        List<TraceEvent> lastTurnOnly = List.of(
                turn(1), toolCall("a"), api("a", "numen.inv.make", "bad_argument"), commandError("a"));
        EvalMetrics.RunMetrics other = EvalMetrics.RunMetrics.of(lastTurnOnly);
        assertEquals(0, other.commandErrors(), "没有下一轮就判不出纠正");
    }

    /** 记录文件里读不成 JSON 的行跳过,不影响别的行。 */
    @Test
    void malformedTranscriptLinesAreSkipped() {
        List<TraceEvent> events = RunTrace.parse(List.of("not json", "", "{\"kind\":\"turn\",\"n\":\"1\"}",
                "{\"no_kind\":true}"));
        assertEquals(1, events.size());
        assertEquals("turn", events.get(0).kind());
        assertEquals("1", events.get(0).field("n"));
    }

    /** 运行没有记录文件时,只报老指标,新指标是 NaN 而不是崩。 */
    @Test
    void aRunWithoutATraceStillAggregatesTheOldMetrics() {
        Run run = new Run("vanilla", "x", "live", 1, "c", "h", "m", true, List.of(), List.of(), "DONE",
                3, 2, 0, 0, 0, 0, 0, 0, null, null, 1000, 20, false, null, "", "transcripts/missing.jsonl",
                null, Map.of(), List.of());
        EvalMetrics.Aggregate m = EvalMetrics.aggregate(List.of(run), Map.of());
        assertEquals(1, m.liveRuns());
        assertEquals(1.0, m.successRate(), EPS);
        assertEquals(3.0, m.turns(), EPS);
        assertTrue(Double.isNaN(m.interruptionRecoveryRate()));
        assertTrue(Double.isNaN(m.selfCorrectionRate()));
        assertTrue(Double.isNaN(m.passK3()), "一个场景不足 k 次,pass^k 没定义");
        assertFalse(m.liveRuns() == 0);
    }

    // ---- 造事件的帮手 ----

    private static TraceEvent turn(int n) {
        return event("turn", Map.of("n", String.valueOf(n)));
    }

    private static TraceEvent toolCall(String id) {
        return event("tool_call", Map.of("id", id));
    }

    private static TraceEvent programEnd(String id, String status) {
        return event("program_end", Map.of("id", id, "status", status));
    }

    private static TraceEvent api(String program, String function, String kind) {
        return event("api_call", Map.of("program", program, "function", function, "error_kind", kind));
    }

    /** 一次程序以 {@code api_args} 失败:program_end 与 tool_result 都写上。 */
    private static TraceEvent commandError(String id) {
        return event("tool_result", Map.of("id", id, "success", "false", "error_class", "api_args"));
    }

    private static TraceEvent event(String kind, Map<String, String> fields) {
        return new TraceEvent(kind, 0, fields);
    }
}
