package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 一次验收跑完后的机器可读报告——字段对着 MineAI 的两份靶场报告
 * ({@code task-harness.ps1} / {@code scenario-harness.ps1})对齐。
 *
 * <h2>为什么报告必须写世界,而不只写模型</h2>
 * 模型的自述不可信,所以证据链是:动作台账({@link ActionRecord},工具层外面记的)
 * + 终局探针({@link ProbeResult},靶场量的)。模型说了什么一句话都不进结论。
 *
 * <h2>字段与 MineAI 的对应</h2>
 * <ul>
 *   <li>{@code State} ↔ {@link #state()}:FINISHED / FAILED / CANCELLED;</li>
 *   <li>{@code Verified} ↔ {@link #verified()}:谓词成没成立,读的是权威状态,不是她的宣告;</li>
 *   <li>{@code FalseClaims} ↔ {@link #falseClaims()}:收尾被驳回的次数(日志 {@code finish rejected});</li>
 *   <li>{@code Aborts} ↔ {@link #aborts()}:没达成而认输的次数(日志 {@code aborted});</li>
 *   <li>{@code Seconds} ↔ {@link #seconds()};</li>
 *   <li>{@code Steps} ↔ {@link #turns()}(Numen 没有编译出来的步骤表,一轮一个推进单位)。</li>
 * </ul>
 * MineAI 的 {@code Plan}(done/total 步骤)在 Numen 侧没有等价物,不硬造。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record HarnessReport(String scenario,
                            String objective,
                            String condition,
                            JsonObject predicate,
                            List<String> deniedCommandRoots,
                            long startedAt,
                            long endedAt,
                            String state,
                            boolean verified,
                            int falseClaims,
                            int aborts,
                            int turns,
                            long tokensUsed,
                            List<ActionRecord> actions,
                            List<ProbeResult> finalProbes) {

    public static final String FINISHED = "FINISHED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static HarnessReport of(String scenario, AcceptanceGoal goal, AcceptanceVerdict verdict,
                                   int falseClaims, int aborts, List<ActionRecord> actions,
                                   String state, long endedAt) {
        return new HarnessReport(
                scenario,
                goal.objective(),
                goal.predicate().condition(),
                goal.predicate().toJson(),
                List.copyOf(goal.whitelist().deniedCommandRoots()),
                goal.startedAt(),
                endedAt,
                state,
                verdict != null && verdict.met(),
                falseClaims,
                aborts,
                goal.turnsExecuted(),
                goal.tokensUsed(),
                List.copyOf(actions),
                verdict == null ? List.of() : List.copyOf(verdict.probes()));
    }

    /** 终局:谓词成立就 {@link #FINISHED},否则 {@link #FAILED}。 */
    public static String stateFor(AcceptanceVerdict verdict) {
        return verdict != null && verdict.met() ? FINISHED : FAILED;
    }

    public long durationMs() {
        return Math.max(0L, endedAt - startedAt);
    }

    public long seconds() {
        return durationMs() / 1000L;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("scenario", scenario);
        o.addProperty("objective", objective);
        o.addProperty("condition", condition);
        o.add("predicate", predicate);
        JsonArray denied = new JsonArray();
        deniedCommandRoots.forEach(denied::add);
        o.add("deniedCommandRoots", denied);
        o.addProperty("startedAt", startedAt);
        o.addProperty("endedAt", endedAt);
        o.addProperty("durationMs", durationMs());
        o.addProperty("seconds", seconds());
        o.addProperty("state", state);
        o.addProperty("verified", verified);
        o.addProperty("falseClaims", falseClaims);
        o.addProperty("aborts", aborts);
        o.addProperty("turns", turns);
        o.addProperty("tokensUsed", tokensUsed);
        JsonArray acts = new JsonArray();
        for (ActionRecord a : actions) {
            acts.add(a.toJson());
        }
        o.add("actions", acts);
        JsonArray probes = new JsonArray();
        for (ProbeResult p : finalProbes) {
            probes.add(p.toJson());
        }
        o.add("finalProbes", probes);
        return o;
    }

    /** 一行一条 JSON 追加到文件——多次运行的报告堆在同一个文件里。 */
    public void appendJsonl(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson().toString() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
