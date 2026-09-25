package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类型化库存目标谓词——MineAI 的主判据形状。
 *
 * <p>判"她手里现在有多少个某物品",对着权威库存判,而不是对着她说的话判。量法用原版的
 * {@code /clear <目标> <物品> 0}:{@code maxCount=0} 只数不删,回话里带着数量。这是靶场
 * 能从服务端控制台读到权威持有量的最省事的一条命令。
 *
 * <p>相对目标({@link Objective#isRelative()})要一条基线:受理目标那一刻量下的持有量。
 * {@link #capture} 就在受理时量一次;绝对目标忽略它。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param objective 目标(类型 / 物品 / 数量)
 * @param subject   量谁,靶场里的选择器(通常是同伴的名字)
 * @param baseline  受理目标那一刻的持有量,相对目标用
 */
public record ObjectivePredicate(Objective objective, String subject, int baseline) implements GoalPredicate {

    static final String COUNT_PROBE = "have";
    private static final Pattern FOUND = Pattern.compile("Found\\s+(\\d+)");

    @Override
    public String condition() {
        return objective.describe();
    }

    @Override
    public List<Probe> probes() {
        return List.of(new Probe(COUNT_PROBE, countCommand(), Expectation.any()));
    }

    @Override
    public AcceptanceVerdict evaluate(Observation obs) {
        String output = obs.output(COUNT_PROBE);
        if (!obs.reachable()) {
            return AcceptanceVerdict.unmet(condition() + " —— 靶场连不上,判不出来:" + obs.error(),
                    List.of(new ProbeResult(COUNT_PROBE, countCommand(), output, false)));
        }
        int have = countFrom(output);
        boolean met = objective.met(have, baseline);
        ProbeResult evidence = new ProbeResult(COUNT_PROBE, countCommand(), output, met);
        String reason = condition() + " (have " + have + "/" + objective.count() + ")";
        return met
                ? AcceptanceVerdict.met(reason, List.of(evidence))
                : AcceptanceVerdict.unmet(reason, List.of(evidence));
    }

    /** 数量探针跑的那一行。 */
    public String countCommand() {
        return "clear " + subject + " " + objective.itemId() + " 0";
    }

    /**
     * 从 {@code /clear} 的回话里读数量。"Found N matching item(s)…" → N;
     * "No items were found…" 或者读不懂 → 0。读不懂当 0 是安全的:宁可判没达成多跑一轮。
     */
    static int countFrom(String output) {
        if (output == null) {
            return 0;
        }
        Matcher m = FOUND.matcher(output);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 受理目标时量一次基线,和 MineAI 的 {@code objectiveBaseline = countOf(...)} 同一时机。 */
    public static ObjectivePredicate capture(Objective objective, String subject, ObservationSource source) {
        ObjectivePredicate probe = new ObjectivePredicate(objective, subject, 0);
        Observation obs = source.observe(probe.probes());
        return new ObjectivePredicate(objective, subject, countFrom(obs.output(COUNT_PROBE)));
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "item");
        o.add("objective", objective.toJson());
        o.addProperty("subject", subject);
        o.addProperty("baseline", baseline);
        return o;
    }

    public static ObjectivePredicate fromJson(JsonObject o) {
        Objective objective = Objective.fromJson(o.has("objective") ? o.getAsJsonObject("objective") : null);
        String subject = o.has("subject") ? o.get("subject").getAsString() : "@s";
        int baseline = o.has("baseline") ? o.get("baseline").getAsInt() : 0;
        return new ObjectivePredicate(objective, subject, baseline);
    }
}
