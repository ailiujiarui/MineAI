package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.RunEnd;
import com.dwinovo.numen.ai.AiLog;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "不能自称完成"这条红线的落点。形状照搬 MineAI 的 {@code AgentController.finishGoal}。
 *
 * <h2>它拦的是什么</h2>
 * 循环内核里,模型回一条没有工具调用的最终回复就算这一轮说完({@link RunEnd.Done})。
 * 于是模型说一句"我挖完了",回合就结束了——它报得准不准,内核管不着。MineAI 的做法是:
 * 收尾前先 {@code objectiveMet(objective)},没达成就把这条收尾<b>驳回</b>,把"你手里有多少、
 * 还差多少"塞回去,让她接着干;驳回次数记进日志的 {@code finish rejected},靶场数它。
 *
 * <p>本守卫挂在 {@code RunEnded(Done)} 上:一次 run 收了尾,不用模型的话,拿
 * {@link GoalPredicate} 对靶场量一次世界。达成才清;没达成 → {@link #falseClaims()} 加一,
 * 推一条续跑回去。驳回超过 {@value #MAX_FINISH_REJECTIONS} 次(与 MineAI 的
 * {@code MAX_OBJECTIVE_NUDGES} 一致)才认输,记一次 {@link #aborts()}。
 *
 * <h2>为什么不在提示词里劝</h2>
 * 提示词能说的是"别自称完成",照做与否是模型的自由。这里钉的是结构:收尾这一步绕不开
 * 世界量测。宣称与裁决分成两个人,它就骗不了自己。
 *
 * <p>纯 JVM。与长期目标 {@code GoalSteward} 是同一个订阅位,同一时刻只该挂一个:
 * 验收用机器谓词,长期目标用评估模型,不要对同一个目标都挂。
 */
public final class CompletionGuard {

    /** 收尾被驳回这么多次还没达成,就认输——抄 MineAI 的 {@code MAX_OBJECTIVE_NUDGES = 4}。 */
    public static final int MAX_FINISH_REJECTIONS = 4;

    private final String name;
    private final AgentLoop loop;
    private final EventQueue inbox;
    private final BooleanSupplier bodyOnFiniteTask;
    private final Function<AcceptanceGoal, AcceptanceVerdict> check;
    private final Consumer<AcceptanceGoal> onCleared;

    private AcceptanceGoal goal;
    private AcceptanceVerdict lastVerdict;
    private int falseClaims;
    private int aborts;

    /**
     * @param bodyOnFiniteTask 身体手上有一件会结束的活——异步任务回执才刚回来、身体还没动完,
     *                         这时对着世界量一定判"没达成",不如等 {@code task_finished} 开了下一轮再判
     * @param check            怎么量:"拿目标谓词对靶场问一次"。真实实现用 {@link #via}
     * @param onCleared        目标收工时的回调(落报告、清盘);可空
     */
    public CompletionGuard(String name, AgentLoop loop, EventQueue inbox, BooleanSupplier bodyOnFiniteTask,
                           Function<AcceptanceGoal, AcceptanceVerdict> check, Consumer<AcceptanceGoal> onCleared) {
        this.name = name;
        this.loop = loop;
        this.inbox = inbox;
        this.bodyOnFiniteTask = bodyOnFiniteTask;
        this.check = check;
        this.onCleared = onCleared;
    }

    /** 用靶场当量测来源的 {@code check}。 */
    public static Function<AcceptanceGoal, AcceptanceVerdict> via(ObservationSource source) {
        return goal -> goal.predicate().evaluate(source.observe(goal.predicate().probes()));
    }

    public AcceptanceGoal goal() {
        return goal;
    }

    /** 最近一次判词;还没判过则 {@code null}。 */
    public AcceptanceVerdict lastVerdict() {
        return lastVerdict;
    }

    /** 收尾了、但世界说没达成——被驳回的自称完成次数(靶场报告里的 {@code FalseClaims})。 */
    public int falseClaims() {
        return falseClaims;
    }

    /** 没达成而认输的次数(靶场报告里的 {@code Aborts}):驳回超限或连续无进展。 */
    public int aborts() {
        return aborts;
    }

    /** 换一个验收目标;计数清零。 */
    public void set(AcceptanceGoal next) {
        this.goal = next;
        this.lastVerdict = null;
        this.falseClaims = 0;
        this.aborts = 0;
    }

    /** 收工。只有"在"和"不在"两种,做完、卡住、跑够、喊停都走这里。 */
    public void clear(String why) {
        if (goal == null) {
            return;
        }
        AcceptanceGoal done = goal;
        AiLog.LOG.info("[numen-entity#{}] 验收目标收工({} 轮):{}", name, done.turnsExecuted(), why);
        goal = null;
        if (onCleared != null) {
            onCleared.accept(done);
        }
    }

    /** 内核事件里验收这一侧要接的——与 {@code GoalSteward} 同一接线法。 */
    public void on(LoopEvent event) {
        switch (event) {
            // 一次 run 说完,正是该拿世界验一次的时刻
            case LoopEvent.RunEnded ended -> {
                if (ended.end() instanceof RunEnd.Done) {
                    steer();
                }
            }
            case LoopEvent.Halted halted -> {
                if (halted.reason().endsGoal() && goal != null) {
                    clear("主人喊停:" + goal.objective());
                }
            }
            case LoopEvent.ModelUsed used -> {
                if (used.purpose() == LoopEvent.Purpose.TURN && goal != null) {
                    goal.addTokens(used.usage().fresh());
                }
            }
            default -> { }
        }
    }

    /**
     * 一次 run 收尾后:量一次世界。达成 → 收工;没达成 → 驳回这次收尾,把"还差什么"推回去。
     *
     * <p>队列里还排着别的、身体还在干活、正停着牌,都先不量——那些本来就会开起下一轮,
     * 到那时再量,省一次白判,也避免异步任务回执刚回来时的假"没达成"。
     */
    private void steer() {
        if (goal == null || loop.hold() != null || !inbox.isEmpty()) {
            return;
        }
        if (bodyOnFiniteTask.getAsBoolean()) {
            return;
        }
        AcceptanceVerdict verdict = check.apply(goal);
        lastVerdict = verdict;
        if (verdict.met()) {
            clear("objective met: " + verdict.reason());
            return;
        }
        // 模型刚刚收的尾,世界说没达成:驳回,记一笔。
        falseClaims++;
        AiLog.LOG.info("[numen-entity#{}] finish rejected, objective unmet ({}/{}), claim {}: {}",
                name, falseClaims, MAX_FINISH_REJECTIONS, falseClaims, verdict.reason());
        if (falseClaims > MAX_FINISH_REJECTIONS) {
            aborts++;
            clear("objective unmet after " + MAX_FINISH_REJECTIONS + " finish claims: " + verdict.reason());
            return;
        }
        if (goal.noteStuck(verdict.reason())) {
            aborts++;
            clear("no progress, giving up: " + verdict.reason());
            return;
        }
        goal.countTurn();
        long now = System.currentTimeMillis();
        loop.push(List.of(new EventQueue.Entry(EventTypes.GOAL, progress(verdict, goal, now), now, false)));
    }

    /** 设定验收目标那一刻交给模型的东西:正文 + 判据 + MineAI 原话"你不能结束目标直到它成立"。 */
    public static String directive(AcceptanceGoal goal) {
        return """
                <acceptance-goal>
                %s
                Objective (verified by the system, you cannot end the goal until it is true): %s
                An external checker measures the world against this objective; it does not read \
                your words. Do not declare it finished yourself. When you stop, the objective is \
                checked anyway.
                </acceptance-goal>""".formatted(goal.objective(), goal.predicate().condition());
    }

    /** 驳回时推回去的续跑块——续跑期间唯一重复出现的文本。 */
    static String progress(AcceptanceVerdict verdict, AcceptanceGoal goal, long nowMs) {
        return """
                <acceptance-result met="false" turn="%d" elapsed="%s">
                NOT MET: %s
                Objective (verified by the system, you cannot end the goal until it is true): %s
                Do not finish; reply with one action that makes progress.
                </acceptance-result>""".formatted(goal.turnsExecuted(), seconds(goal.elapsedMs(nowMs)),
                verdict.reason(), goal.predicate().condition());
    }

    private static String seconds(long ms) {
        return (ms / 1000L) + "s";
    }
}
