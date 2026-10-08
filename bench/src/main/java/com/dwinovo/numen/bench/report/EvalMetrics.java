package com.dwinovo.numen.bench.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 一组配对的真实模型运行的指标聚合:五项老指标(成功率、pass^k、命令出错率、轮数、墙钟)与三项新指标(打断恢复成本、
 * 征询的真实成本、自我纠正率)。纯 JVM,只读 {@link Run} 与记录文件({@link RunTrace}),不碰 Minecraft,可以单测、可以拿去别处用。
 *
 * <p>老指标与 {@link Summary} 同口径:成功率是真实模型里通过的比例,pass^{@value Summary#K} 用 {@link Stats#passHatK}
 * (k 次全成功的无偏估计,按场景算再对场景取均值),命令出错率是工具结果 {@code success:false} 占工具调用数的比例,
 * 轮数是每次运行调模型次数的均值,墙钟是秒均值。
 *
 * <p>三项新指标都从记录文件的原始事件读:
 * <ul>
 *   <li><b>打断恢复成本</b>:一次程序被停下({@code program_end} 的 {@code status=stopped})算一次打断;从它之后按次序找
 *       "第一次有效动作"(一次成功、且不是 {@code numen.scan.*}、不是 {@code numen.api.*} 的 API 调用),数这之前调了多少次
 *       {@code numen.scan.*}(重定方向要扫几次)、隔了几条轮次边界;在一组里对被恢复的打断求均值,恢复率是找到有效动作的比例。</li>
 *   <li><b>征询的真实成本</b>:每次到达主人的征询({@code consent})算一次;拒绝看答复 {@code DENY},超时/问不到看 {@code PENDING};
 *       一个任务算"因征询被放弃"如果它以 {@link EndReason#DENIED} 收场,或者出现过拒绝/悬而未决且最终没过。</li>
 *   <li><b>自我纠正率</b>:一次 {@code api_args} 命令错(程序结局的种类是 {@code bad_argument}/{@code no_function},
 *       同 {@code error_class=api_args})之后,下一轮有工具调用且没有再犯同样的命令错,算纠正一次;分母是后面还有轮次的命令错。</li>
 * </ul>
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /**
     * 一组运行的指标。
     *
     * @param liveRuns                  真实模型的运行次数
     * @param successes                 其中通过几次
     * @param successRate               成功率
     * @param scenarios                 pass^k 有样本的场景数(n≥k)
     * @param passK3                    各场景 pass^k 的均值;一个都不够 k 次是 NaN
     * @param commandErrorRate          工具结果失败数 ÷ 工具调用数
     * @param turns                     每次运行的轮数均值
     * @param wallSeconds               每次运行的墙钟秒均值
     * @param interruptions             打断次数(被停下的程序)
     * @param interruptionsRecovered    之后找到有效动作的打断次数
     * @param interruptionRecoveryRate  恢复率;没有打断是 NaN
     * @param scansToReorient           被恢复的打断里,到第一次有效动作前 {@code numen.scan.*} 的均值;没有样本是 NaN
     * @param turnsToEffective          被恢复的打断里,到第一次有效动作隔的轮次边界均值;没有样本是 NaN
     * @param consentPrompts            到达主人的征询次数
     * @param consentDenied             主人按了拒绝的次数
     * @param consentTimedOut           悬而未决(主人不在/超时)的次数
     * @param consentAbandonedTasks     因征询被放弃的任务数
     * @param consentAbandonedFraction  因征询被放弃的任务占比;没有运行是 NaN
     * @param commandErrors             后面还有轮次、可判纠正的命令错次数
     * @param selfCorrections           其中下一轮改对、没有再犯的次数
     * @param selfCorrectionRate        自我纠正率;没有可判样本是 NaN
     */
    public record Aggregate(int liveRuns, int successes, double successRate, int scenarios, double passK3,
                            double commandErrorRate, double turns, double wallSeconds,
                            int interruptions, int interruptionsRecovered, double interruptionRecoveryRate,
                            double scansToReorient, double turnsToEffective,
                            int consentPrompts, int consentDenied, int consentTimedOut,
                            int consentAbandonedTasks, double consentAbandonedFraction,
                            int commandErrors, int selfCorrections, double selfCorrectionRate) {
    }

    /**
     * 聚合一组记录。
     *
     * @param runs   全部记录(两种基线与真实模型);只有 {@link Variant#LIVE} 进指标
     * @param traces 记录文件路径({@link Run#transcript()})到原始事件的表;缺的运行在新指标上没有样本
     */
    public static Aggregate aggregate(List<Run> runs, Map<String, List<TraceEvent>> traces) {
        List<Run> live = runs.stream().filter(Run::live).toList();
        int n = live.size();
        int c = Summary.passes(live);
        double successRate = n == 0 ? Double.NaN : c / (double) n;

        List<Double> passK = new ArrayList<>();
        Map<String, List<Run>> byScenario = new LinkedHashMap<>();
        for (Run run : live) {
            byScenario.computeIfAbsent(run.suite() + "/" + run.scenario(), k -> new ArrayList<>()).add(run);
        }
        for (List<Run> group : byScenario.values()) {
            if (group.size() >= Summary.K) {
                passK.add(Stats.passHatK(group.size(), Summary.passes(group), Summary.K));
            }
        }
        double passK3 = mean(passK.stream().mapToDouble(Double::doubleValue).toArray());

        long toolCalls = live.stream().mapToLong(Run::toolCalls).sum();
        long toolErrors = live.stream().mapToLong(Run::toolErrors).sum();
        double commandErrorRate = toolCalls == 0 ? Double.NaN : toolErrors / (double) toolCalls;

        int interruptions = 0;
        int recovered = 0;
        long scans = 0;
        long turns = 0;
        int consentPrompts = 0;
        int consentDenied = 0;
        int consentTimedOut = 0;
        int abandoned = 0;
        int commandErrors = 0;
        int corrections = 0;
        for (Run run : live) {
            List<TraceEvent> events = traces.getOrDefault(run.transcript(), List.of());
            RunMetrics m = RunMetrics.of(events);
            interruptions += m.interruptions();
            recovered += m.recovered();
            scans += m.scans();
            turns += m.turnsToEffective();
            consentPrompts += m.consentPrompts();
            consentDenied += m.denied();
            consentTimedOut += m.timedOut();
            commandErrors += m.commandErrors();
            corrections += m.corrections();
            boolean consentFailed = m.denied() > 0 || m.timedOut() > 0;
            if (EndReason.DENIED.name().equals(run.end()) || (consentFailed && !run.passed())) {
                abandoned++;
            }
        }
        return new Aggregate(n, c, successRate, passK.size(), passK3, commandErrorRate,
                n == 0 ? Double.NaN : mean(live, Run::turns),
                n == 0 ? Double.NaN : mean(live, r -> r.wallMs() / 1000.0),
                interruptions, recovered, interruptions == 0 ? Double.NaN : recovered / (double) interruptions,
                recovered == 0 ? Double.NaN : scans / (double) recovered,
                recovered == 0 ? Double.NaN : turns / (double) recovered,
                consentPrompts, consentDenied, consentTimedOut, abandoned,
                n == 0 ? Double.NaN : abandoned / (double) n,
                commandErrors, corrections, commandErrors == 0 ? Double.NaN : corrections / (double) commandErrors);
    }

    private static double mean(List<Run> runs, ToDoubleFunction<Run> f) {
        return runs.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** 一次程序在记录文件里的一段:它属于哪一轮、结局如何、里面每次 API 调用。 */
    private static final class Program {
        final String id;
        final int turn;
        final int order;
        String errorClass = "";
        boolean stopped;
        final List<ApiCall> calls = new ArrayList<>();

        Program(String id, int turn, int order) {
            this.id = id;
            this.turn = turn;
            this.order = order;
        }

        boolean commandError() {
            return errorClass.equals("api_args");
        }
    }

    private record ApiCall(String function, String kind) {
        boolean ok() {
            return kind == null || kind.isEmpty();
        }
    }

    /** 一次运行按记录文件算出来的三项新指标。 */
    record RunMetrics(int interruptions, int recovered, int scans, int turnsToEffective,
                      int consentPrompts, int denied, int timedOut,
                      int commandErrors, int corrections) {

        static RunMetrics of(List<TraceEvent> events) {
            int turn = 0;
            int order = 0;
            Map<String, Program> byId = new LinkedHashMap<>();
            List<Program> programs = new ArrayList<>();
            List<Integer> turnOrder = new ArrayList<>();
            int consentPrompts = 0;
            int denied = 0;
            int timedOut = 0;
            for (TraceEvent e : events) {
                switch (e.kind()) {
                    case "turn" -> {
                        turn++;
                        turnOrder.add(turn);
                    }
                    case "tool_call" -> {
                        Program p = new Program(e.field("id"), turn, order++);
                        byId.put(p.id, p);
                        programs.add(p);
                    }
                    case "program_end" -> {
                        Program p = byId.get(e.field("id"));
                        if (p != null && "stopped".equals(e.field("status"))) {
                            p.stopped = true;
                        }
                    }
                    case "tool_result" -> {
                        Program p = byId.get(e.field("id"));
                        if (p != null) {
                            p.errorClass = e.field("error_class");
                        }
                    }
                    case "api_call" -> {
                        Program p = byId.get(e.field("program"));
                        if (p != null) {
                            p.calls.add(new ApiCall(e.field("function"), e.field("error_kind")));
                        }
                    }
                    case "consent" -> {
                        consentPrompts++;
                        String answer = e.field("answer");
                        if (answer.equals("DENY")) {
                            denied++;
                        } else if (answer.equals("PENDING")) {
                            timedOut++;
                        }
                    }
                    default -> { }
                }
            }

            int commandErrors = 0;
            int corrections = 0;
            Map<Integer, List<Program>> byTurn = new LinkedHashMap<>();
            for (Program p : programs) {
                byTurn.computeIfAbsent(p.turn, k -> new ArrayList<>()).add(p);
            }
            for (int i = 0; i < turnOrder.size(); i++) {
                List<Program> here = byTurn.getOrDefault(turnOrder.get(i), List.of());
                boolean erred = here.stream().anyMatch(Program::commandError);
                if (!erred || i + 1 >= turnOrder.size()) {
                    continue;
                }
                commandErrors++;
                List<Program> next = byTurn.getOrDefault(turnOrder.get(i + 1), List.of());
                if (!next.isEmpty() && next.stream().noneMatch(Program::commandError)) {
                    corrections++;
                }
            }

            int interruptions = 0;
            int recovered = 0;
            int scans = 0;
            int turns = 0;
            for (int i = 0; i < programs.size(); i++) {
                Program p = programs.get(i);
                if (!p.stopped) {
                    continue;
                }
                interruptions++;
                int found = -1;
                int countedScans = 0;
                outer:
                for (int j = i + 1; j < programs.size() && found < 0; j++) {
                    Program q = programs.get(j);
                    for (ApiCall call : q.calls) {
                        if (isScan(call.function())) {
                            countedScans++;
                        } else if (call.ok() && isEffective(call.function())) {
                            found = j;
                            break outer;
                        }
                    }
                }
                if (found >= 0) {
                    recovered++;
                    scans += countedScans;
                    turns += Math.max(0, programs.get(found).turn - p.turn);
                }
            }
            return new RunMetrics(interruptions, recovered, scans, turns,
                    consentPrompts, denied, timedOut, commandErrors, corrections);
        }

        /** 查四周的函数:{@code numen.scan} 组的都是。 */
        static boolean isScan(String function) {
            return function.equals("numen.scan") || function.startsWith("numen.scan.");
        }

        /** 一次有效动作:成了,而且不是在扫、也不是在查帮助。 */
        static boolean isEffective(String function) {
            return !isScan(function) && !function.startsWith("numen.api");
        }
    }
}
