package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

/**
 * 一次验收任务:目标正文 + 机器判据 + 这次靶场的白名单,加上轮次与进度簿记。
 *
 * <p>与长期目标 {@code GoalState} 的区别只有一处,也是要害:<b>收工与否不看模型,看谓词。</b>
 * 没有状态机,只有"在"和"不在";做完、卡住、跑够轮次、主人喊停,结果都是清掉,区别只在报告里那句话。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class AcceptanceGoal {

    /** 连着这么多次判不出进展就收工。 */
    public static final int STUCK_STREAK_TO_GIVE_UP = 2;
    /** 目标正文上限。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private final String objective;
    private final GoalPredicate predicate;
    private final Whitelist whitelist;
    private final long startedAt;
    private int turnsExecuted;
    private long tokensUsed;
    private String lastReason;
    private int stuckStreak;

    private AcceptanceGoal(String objective, GoalPredicate predicate, Whitelist whitelist, long startedAt) {
        this.objective = objective;
        this.predicate = predicate;
        this.whitelist = whitelist;
        this.startedAt = startedAt;
    }

    public static AcceptanceGoal of(String objective, GoalPredicate predicate, Whitelist whitelist, long nowMs) {
        String s = objective == null ? "" : objective.strip();
        if (s.length() > MAX_OBJECTIVE_CHARS) {
            s = s.substring(0, MAX_OBJECTIVE_CHARS);
        }
        return new AcceptanceGoal(s, predicate, whitelist, nowMs);
    }

    public String objective() {
        return objective;
    }

    public long startedAt() {
        return startedAt;
    }

    public GoalPredicate predicate() {
        return predicate;
    }

    public Whitelist whitelist() {
        return whitelist;
    }

    public int turnsExecuted() {
        return turnsExecuted;
    }

    public long tokensUsed() {
        return tokensUsed;
    }

    public String lastReason() {
        return lastReason;
    }

    public long elapsedMs(long nowMs) {
        return Math.max(0L, nowMs - startedAt);
    }

    public void countTurn() {
        turnsExecuted++;
    }

    public void addTokens(long n) {
        if (n > 0) {
            tokensUsed += n;
        }
    }

    /**
     * 记一次判词是不是"原地打转":理由跟上次一字不差才算数。判的人不是模型,是世界——
     * 它每次给的"还差什么"是量测出来的,不像自述那样会漂。
     *
     * @return 连着这么多次没进展,该收工了没有
     */
    public boolean noteStuck(String reason) {
        String r = reason == null ? "" : reason;
        stuckStreak = r.equals(lastReason) ? stuckStreak + 1 : 0;
        lastReason = r;
        return stuckStreak >= STUCK_STREAK_TO_GIVE_UP;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("objective", objective);
        o.add("predicate", predicate.toJson());
        o.add("whitelist", whitelist.toJson());
        o.addProperty("startedAt", startedAt);
        o.addProperty("turnsExecuted", turnsExecuted);
        o.addProperty("tokensUsed", tokensUsed);
        o.addProperty("stuckStreak", stuckStreak);
        if (lastReason != null) {
            o.addProperty("lastReason", lastReason);
        }
        return o;
    }

    public static AcceptanceGoal fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        String objective = o.has("objective") ? o.get("objective").getAsString() : "";
        if (objective.isBlank()) {
            return null;
        }
        GoalPredicate predicate = GoalPredicate.fromJson(o.getAsJsonObject("predicate"));
        Whitelist whitelist = Whitelist.harnessDefault();
        long startedAt = o.has("startedAt") ? o.get("startedAt").getAsLong() : 0L;
        AcceptanceGoal g = new AcceptanceGoal(objective, predicate, whitelist, startedAt);
        g.turnsExecuted = o.has("turnsExecuted") ? o.get("turnsExecuted").getAsInt() : 0;
        g.tokensUsed = o.has("tokensUsed") ? o.get("tokensUsed").getAsLong() : 0L;
        g.stuckStreak = o.has("stuckStreak") ? o.get("stuckStreak").getAsInt() : 0;
        g.lastReason = o.has("lastReason") && !o.get("lastReason").isJsonNull()
                ? o.get("lastReason").getAsString() : null;
        return g;
    }
}
