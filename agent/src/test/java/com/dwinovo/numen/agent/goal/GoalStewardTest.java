package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.LoopHarness;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期目标挂在一个真的循环内核上:一次 run 说完才评估,评估不是 run,没做完就接着推一条续跑,
 * 做完、卡住、主人按停止就收工。
 */
class GoalStewardTest extends LoopHarness {

    private final List<GoalState> persisted = new ArrayList<>();
    private boolean bodyBusy;
    private GoalSteward goals;

    @BeforeEach
    void setUpGoals() {
        goals = new GoalSteward("test", loop, transcript, inbox, () -> "<runtime_state/>", () -> bodyBusy,
                this::persistSnapshot, null);
        loop.subscribe(goals::on);
    }

    /** 主人定了目标,她答了一句——一次 run 就这么说完了。 */
    private GoalState goalSetAndFirstRunDone(String objective) {
        return goalSetAndFirstRunDone(goals, objective);
    }

    private GoalState goalSetAndFirstRunDone(GoalSteward steward, String objective) {
        GoalState goal = GoalState.of(objective, T0);
        assertTrue(steward.set(goal), "活着、没被外接驾驶:当场交给她");
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY,
                EventQueue.query("/goal " + objective) + "\n" + GoalPrompts.initialDirective(goal), 0, false)));
        model.last().say("好,这就去");
        return goal;
    }

    /** 另起一个挂了假判官/假验证者的目标管家;默认那个 goal 为 null,不受影响。 */
    private GoalSteward stewardWith(GoalJudge judge, GoalVerifier verifier) {
        GoalSteward steward = new GoalSteward("test", loop, transcript, inbox, () -> "<runtime_state/>",
                () -> bodyBusy, this::persistSnapshot, null, judge, verifier);
        loop.subscribe(steward::on);
        return steward;
    }

    private void persistSnapshot(GoalState goal) {
        persisted.add(goal == null ? null : GoalState.fromJson(goal.toJson()));
    }

    /** 一个永远判"达成"、并附带这条机检宣称的判官。 */
    private static GoalJudge claimMet(String claim) {
        return (goal, facts, since, cancel, onDone) -> onDone.accept(GoalJudge.Outcome.of(
                new GoalPrompts.Verdict(true, false, "看起来做完了", claim), 5L));
    }

    private boolean isEvaluation(Call call) {
        return GoalPrompts.evaluatorSystem().equals(call.request().systemPrompt());
    }

    private void verdict(String line) {
        model.last().onDone().accept(new ModelOutcome.Answered(new AssistantTurn(line, List.of(), null),
                new Usage(100, 20, 0, 0)));
    }

    @Test
    void aFinishedRunIsJudgedByASeparateCallThatIsNotARun() {
        goalSetAndFirstRunDone("挖 64 个铁");

        assertTrue(isEvaluation(model.last()), "说完之后另开一次调用来判");
        assertTrue(model.last().request().tools().isEmpty(), "判的人不带工具");
        assertNull(loop.status().phase(), "评估不占内核");
        assertTrue(model.last().lastUser().contains("挖 64 个铁"));
    }

    @Test
    void notMetYetPushesAContinuationThatStartsTheNextRun() {
        GoalState goal = goalSetAndFirstRunDone("挖 64 个铁");
        int callsBefore = model.calls.size();

        verdict(GoalPrompts.NOT_MET + ": 背包里只有 20 个铁");

        assertEquals(callsBefore + 1, model.calls.size(), "续跑是急件,当场开下一次 run");
        assertFalse(isEvaluation(model.last()));
        assertTrue(model.last().lastUser().contains("背包里只有 20 个铁"), "只补评估器那句还差什么");
        assertEquals(2, goal.turnsExecuted());
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson(), "续跑一轮就落盘一次");
    }

    @Test
    void metEndsTheGoalQuietly() {
        goalSetAndFirstRunDone("挖 64 个铁");
        int callsBefore = model.calls.size();

        verdict(GoalPrompts.MET + ": 背包里有 64 个铁锭");

        assertNull(goals.goal());
        assertNull(persisted.get(persisted.size() - 1), "收工也落盘");
        assertEquals(callsBefore, model.calls.size(), "收工不再开 run");
    }

    @Test
    void aMetVerdictWhoseClaimTheWorldDeniesIsNotCleared() {
        GoalVerifier denies = (goal, claim, onDone) -> onDone.accept(new GoalVerifier.Result(
                false, "expected 3 x minecraft:iron_ingot, actual 1 x minecraft:iron_ingot"));
        GoalSteward steward = stewardWith(claimMet("have minecraft:iron_ingot 3"), denies);

        GoalState goal = goalSetAndFirstRunDone(steward, "挖 3 个铁");

        assertEquals(goal, steward.goal(), "世界不认,就不能收工");
        assertTrue(goal.lastReason().contains("world does not confirm"), goal.lastReason());
        assertEquals(2, goal.turnsExecuted(), "没核对过也算一轮,推她接着做");
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson(), "不收工也要落盘");
        assertTrue(model.last().lastUser().contains("expected 3 x minecraft:iron_ingot"),
                "下一轮得让她看见世界实际长什么样");
    }

    @Test
    void aMetVerdictTheVerifierCouldNotAnswerIsNotCleared() {
        GoalVerifier unmeasured = (goal, claim, onDone) ->
                onDone.accept(GoalVerifier.Result.unmeasured("verify returned no verdict"));
        GoalSteward steward = stewardWith(claimMet("have minecraft:iron_ingot 3"), unmeasured);

        GoalState goal = goalSetAndFirstRunDone(steward, "挖 3 个铁");

        assertEquals(goal, steward.goal(), "量不出来 ≠ 世界认了,不能收工");
        assertTrue(goal.lastReason().contains("could not be checked"), goal.lastReason());
        assertEquals(2, goal.turnsExecuted(), "没核对上也算一轮,推她接着做");
        assertTrue(model.last().lastUser().contains("could not be checked"),
                "下一轮得让她知道这次核对没量出结果");
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson(),
                "未核对原因与续跑额度必须一起落盘");
    }

    @Test
    void aClaimWithoutAVerifierIsUnmeasuredAndRemainsActive() {
        GoalSteward steward = stewardWith(claimMet("have minecraft:iron_ingot 3"), null);

        GoalState goal = goalSetAndFirstRunDone(steward, "挖 3 个铁");

        assertEquals(goal, steward.goal());
        assertTrue(goal.lastReason().contains("no verifier"));
        assertTrue(goal.lastReason().contains("could not be checked"));
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson());
    }

    @Test
    void failedJudgementIsPersistedWithoutRetryAndRecoversAfterOwnerInput() {
        GoalState goal = goalSetAndFirstRunDone("挖 64 个铁");
        int callsBefore = model.calls.size();
        int turnsBefore = goal.turnsExecuted();
        int stuckBefore = goal.stuckStreak();

        model.last().onDone().accept(new ModelOutcome.Failed("endpoint unavailable", false));

        assertEquals(goal, goals.goal(), "失败保留目标");
        assertTrue(goal.lastReason().contains("endpoint unavailable"), "现有 /goal 展示读取 lastReason");
        GoalState restored = persisted.get(persisted.size() - 1);
        assertEquals(goal.toJson(), restored.toJson(), "失败原因不能只留在内存对象里");
        assertEquals(turnsBefore, restored.turnsExecuted());
        assertEquals(stuckBefore, restored.stuckStreak(), "传输失败不算目标原地打转");
        assertNull(loop.status().hold(), "旁路失败不锁住正常 run");
        assertNull(loop.status().phase());
        loop.tick();
        assertEquals(callsBefore, model.calls.size(), "不新增重试或 tick 调度");

        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, EventQueue.query("继续看看"), 0, false)));
        model.last().say("我再检查一下");
        assertTrue(isEvaluation(model.last()), "下一个合法 run 完成后重新评估");
        assertTrue(model.last().lastUser().contains("endpoint unavailable"), "判官读到持久化的失败原因");
        verdict("NOT_MET: 还差 10 个铁");

        assertEquals("还差 10 个铁", goal.lastReason());
        assertEquals(turnsBefore + 1, goal.turnsExecuted());
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson());
        assertTrue(restored.lastReason().contains("endpoint unavailable"), "旧落盘快照不会被恢复后的修改污染");
    }

    @Test
    void semanticCompletionWithoutAClaimDoesNotInvokeDeterministicVerification() {
        GoalSteward steward = stewardWith(claimMet(null), (goal, claim, onDone) -> {
            throw new AssertionError("没有机检宣称的语义目标不派核对");
        });

        goalSetAndFirstRunDone(steward, "陪主人聊一会儿");

        assertNull(steward.goal());
        assertNull(persisted.get(persisted.size() - 1));
        assertTrue(GoalPrompts.evaluatorSystem().contains("semantic judgment"));
    }

    @Test
    void aMetVerdictTheWorldConfirmsIsCleared() {        GoalVerifier confirms = (goal, claim, onDone) ->
                onDone.accept(new GoalVerifier.Result(true, "expected/actual match"));
        GoalSteward steward = stewardWith(claimMet("have minecraft:iron_ingot 3"), confirms);

        goalSetAndFirstRunDone(steward, "挖 3 个铁");

        assertNull(steward.goal(), "世界认了才收工");
        assertNull(persisted.get(persisted.size() - 1), "收工也落盘");
    }

    @Test
    void theJudgeWaitsWhileTheBodyIsStillWorking() {
        bodyBusy = true;
        goalSetAndFirstRunDone("挖 64 个铁");

        assertFalse(isEvaluation(model.last()), "身体还在挖:每个往返问一次挖完没有只是烧 token");
    }

    @Test
    void theJudgeWaitsWhenSomethingElseIsQueued() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>先过来</query>", 0, false)));
        control(EventTypes.COMPACT);   // run 里主人按了整理:它排在队首,这次 run 说完就走
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>整理完再说这句</query>", 0, false)));
        model.last().say("来了");

        assertFalse(model.calls.stream().anyMatch(this::isEvaluation),
                "队里还排着要她回应的话,那本来就会开起下一次 run,做完再判");
        assertEquals(com.dwinovo.numen.agent.loop.Phase.COMPACT, loop.status().phase());
    }

    @Test
    void aQueuedControlEntryDoesNotStallTheGoal() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>先过来</query>", 0, false)));
        control(EventTypes.COMPACT);
        model.last().say("来了");

        Call judging = model.calls.stream().filter(this::isEvaluation).findFirst()
                .orElseThrow(() -> new AssertionError("整理执行完不会开 run,等它就是让目标停在这儿"));
        Call compaction = model.last();
        assertEquals(com.dwinovo.numen.agent.loop.Phase.COMPACT, loop.status().phase());

        judging.onDone().accept(new ModelOutcome.Answered(new AssistantTurn(
                GoalPrompts.NOT_MET + ": 背包里只有 20 个铁", List.of(), null), new Usage(100, 20, 0, 0)));
        assertEquals(com.dwinovo.numen.agent.loop.Phase.COMPACT, loop.status().phase(), "续跑排着,等整理落地");

        compaction.say("<summary>之前挖了矿</summary>");

        assertEquals(com.dwinovo.numen.agent.loop.Phase.MODEL, loop.status().phase(), "整理落地后续跑接上");
        assertTrue(model.last().lastUser().contains("背包里只有 20 个铁"));
    }

    @Test
    void overheardTalkQueuedAtTheEndDoesNotStallTheGoal() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>/goal 挖 64 个铁</query>", 0, false)));
        overhears("[阿岚] 我去东边");   // 她最后那次调模型期间,同伴在群里说了一句
        model.last().say("好,这就去");

        assertTrue(isEvaluation(model.last()), "旁听开不起 run,等它就是让目标停在这儿");

        verdict(GoalPrompts.NOT_MET + ": 背包里只有 20 个铁");

        String injected = model.last().lastUser();
        assertTrue(injected.contains("背包里只有 20 个铁") && injected.contains("[阿岚] 我去东边"),
                "续跑那一轮把旁听捎带进去");
    }

    @Test
    void aNewRunVoidsTheJudgementInFlight() {
        goalSetAndFirstRunDone("挖 64 个铁");
        Call judging = model.last();

        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>等等</query>", 0, false)));

        assertTrue(judging.cancel().isCancelled(), "判的已经不是眼前的局面了");
    }

    @Test
    void stopEndsTheGoal() {
        goalSetAndFirstRunDone("挖 64 个铁");

        loop.halt(HaltReason.OWNER_STOP);

        assertNull(goals.goal(), "按了停止还自己续上的话,停止键就成了摆设");
    }

    @Test
    void conversationUsageIsBilledToTheGoal() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);

        goals.on(new LoopEvent.ModelUsed(new Usage(1000, 100, 0, 0), LoopEvent.Purpose.TURN));
        goals.on(new LoopEvent.ModelUsed(new Usage(9999, 999, 0, 0), LoopEvent.Purpose.COMPACT));

        assertEquals(1100, goal.tokensUsed(), "整理记忆不是为这个目标花的");
    }

    @Test
    void aGoalSetWhileDeadIsKeptButNotHandedOver() {
        loop.halt(HaltReason.DEATH);
        GoalState goal = GoalState.of("挖 64 个铁", T0);

        assertFalse(goals.set(goal), "死着的时候交出去也只会躺着");
        assertEquals(goal, goals.goal());
        assertEquals(goal.toJson(), persisted.get(persisted.size() - 1).toJson());
    }

    @Test
    void theJudgeReadsBackToTheGoalDirectiveAndNoFurther() {
        transcript.addUser("<query>很早以前的一句</query>");
        goalSetAndFirstRunDone("挖 64 个铁");

        String query = model.last().lastUser();
        assertTrue(query.contains("好,这就去"));
        assertFalse(query.contains("很早以前的一句"), "目标设定之前的事跟这个目标无关");
    }

    @Test
    void theDirectiveIsRecognisedByTheSameMarkThatWritesIt() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        assertTrue(GoalPrompts.isDirective(GoalPrompts.initialDirective(goal)));
        assertFalse(GoalPrompts.isDirective(GoalPrompts.progress("还差", goal, T0)));
        assertFalse(GoalPrompts.isDirective(new ConvoState.Msg.User("挖 64 个铁").content()));
    }

    @Test
    void aMetVerdictCanCarryAMachineCheckableClaim() {
        var v = GoalPrompts.readVerdict("MET: 背包里有 3 个铁锭\nVERIFY: have minecraft:iron_ingot 3");
        assertTrue(v.met());
        assertEquals("背包里有 3 个铁锭", v.reason());
        assertEquals("have minecraft:iron_ingot 3", v.verify());
    }

    @Test
    void verifyNoneOrAbsentMeansThereIsNoClaim() {
        assertNull(GoalPrompts.readVerdict("MET: 有了\nVERIFY: none").verify());
        assertNull(GoalPrompts.readVerdict("MET: 有了\nVERIFY:").verify());
        assertNull(GoalPrompts.readVerdict("MET: 有了").verify());
        assertNull(GoalPrompts.readVerdict("NOT_MET: 还差\nVERIFY: have minecraft:iron_ingot 3").verify(),
                "没达成的判词不附带宣称");
        assertEquals("block minecraft:torch 120 64 -3",
                GoalPrompts.readVerdict("MET: 放好了\nverify: block minecraft:torch 120 64 -3").verify());
    }
}
