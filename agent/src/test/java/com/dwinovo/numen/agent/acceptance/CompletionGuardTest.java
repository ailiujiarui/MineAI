package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.LoopHarness;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "不能自称完成"这条红线的端到端证明:用真的 {@link AgentLoop},假的量测来源。
 *
 * <p>模型回一条最终回复,内核就 {@code RunEnded(Done)} 了。本测试钉的是:只要世界谓词
 * 没成立,这一收尾就被驳回——目标还在,续跑被推回队列,驳回次数进 falseClaims;
 * 驳回超过上限才认输,记一次 aborts。判据形状与 MineAI 的 {@code finishGoal} 一致。
 */
class CompletionGuardTest extends LoopHarness {

    private ActionQueue queue;
    private CompletionGuard guard;
    private final AtomicReference<AcceptanceVerdict> nextVerdict = new AtomicReference<>();
    private final AtomicBoolean cleared = new AtomicBoolean();

    @BeforeEach
    void installAcceptanceGuard() {
        queue = new ActionQueue(tools, Whitelist.harnessDefault());
        loop = new AgentLoop("test", model, queue, transcript, inbox, memory, host);
        loop.subscribe(events::add);
        guard = new CompletionGuard("test", loop, inbox, () -> false,
                goal -> nextVerdict.get(), goal -> cleared.set(true));
        loop.subscribe(guard::on);
    }

    private AcceptanceGoal aProbeGoal() {
        GoalPredicate predicate = GoalPredicate.all("a diamond block at 0 -60 0", List.of(
                new Probe("blk", "execute if block 0 -60 0 minecraft:diamond_block", Expectation.testPassed())));
        return AcceptanceGoal.of("place a diamond block at 0 -60 0", predicate, Whitelist.harnessDefault(), T0);
    }

    private AcceptanceGoal aStockGoal() {
        GoalPredicate predicate = GoalPredicate.objective(
                Objective.haveItem("minecraft:iron_ingot", 3), "numen", 0);
        return AcceptanceGoal.of("hold 3 iron ingots", predicate, Whitelist.harnessDefault(), T0);
    }

    @Test
    void aFinalReplyCannotEndAnUnmetGoal() {
        AcceptanceGoal goal = aProbeGoal();
        nextVerdict.set(AcceptanceVerdict.unmet("blk Test passed", List.of()));
        guard.set(goal);
        cleared.set(false);

        ownerSays("place a diamond block at 0 -60 0");
        assertEquals(1, model.calls.size());
        model.last().callTools(new LlmToolCall("t1", "mine", "{}"));
        tools.finish("t1");
        assertEquals(1, queue.ledger().size());
        assertEquals(2, model.calls.size());

        // 模型宣称做完了——但世界谓词说没达成。
        model.last().say("I placed the diamond block. Done.");

        assertSame(goal, guard.goal(), "没达成就不许收工");
        assertEquals(1, guard.falseClaims(), "这次自称完成该被驳回");
        assertFalse(cleared.get());
        assertEquals(3, model.calls.size(), "驳回后该开新的一轮,且只开一轮");
        assertTrue(model.last().lastUser().contains("NOT MET"), "续跑块该把差距讲清楚");
    }

    @Test
    void theGoalClearsOnlyWhenTheWorldPredicateHolds() {
        AcceptanceGoal goal = aProbeGoal();
        nextVerdict.set(AcceptanceVerdict.met("blk matched Test passed", List.of()));
        guard.set(goal);
        cleared.set(false);

        ownerSays("place a diamond block at 0 -60 0");
        model.last().say("done");

        assertNull(guard.goal());
        assertTrue(cleared.get());
        assertEquals(0, guard.falseClaims());
        assertEquals(0, guard.aborts());
    }

    @Test
    void aCommandThatCouldFakeTheGoalNeverReachesTheToolLayer() {
        guard.set(aProbeGoal());
        nextVerdict.set(AcceptanceVerdict.unmet("still missing", List.of()));

        ownerSays("make a diamond block");
        model.last().callTools(new LlmToolCall("cheat", "command",
                "{\"command\":\"/setblock 0 -60 0 minecraft:diamond_block\"}"));

        assertTrue(tools.batch.isEmpty(), "被白名单拦的命令不许进工具层");
        assertEquals(1, queue.ledger().size());
        assertTrue(queue.ledger().get(0).denied());
        assertTrue(transcript.snapshot().stream().anyMatch(
                m -> m instanceof ConvoState.Msg.Tool t && t.content().contains("acceptance whitelist")));
        assertEquals(2, model.calls.size(), "拒绝也要结算,让模型拿到回执");
    }

    @Test
    void aGoalThatKeepsHittingTheSameWallGivesUp() {
        AcceptanceGoal goal = aProbeGoal();
        nextVerdict.set(AcceptanceVerdict.unmet("blk still not placed", List.of()));
        guard.set(goal);

        ownerSays("place a diamond block at 0 -60 0");
        for (int i = 0; i < 3; i++) {
            model.last().say("done");
        }

        assertNull(guard.goal(), "连着同一堵墙就该收工,不能无限转");
        assertEquals(3, guard.falseClaims());
        assertEquals(1, guard.aborts());
    }

    @Test
    void finishClaimsAreRejectedUpToTheCapThenItAborts() {
        AcceptanceGoal goal = aProbeGoal();
        guard.set(goal);
        ownerSays("place a diamond block at 0 -60 0");

        // 每次给一个不同的理由,绕开 stuck 早退,单测驳回上限(与 MineAI 的 4 一致)。
        for (int i = 1; i <= CompletionGuard.MAX_FINISH_REJECTIONS; i++) {
            nextVerdict.set(AcceptanceVerdict.unmet("still missing, attempt " + i, List.of()));
            model.last().say("done");
            assertSame(goal, guard.goal(), "第 " + i + " 次驳回后目标还在");
        }

        nextVerdict.set(AcceptanceVerdict.unmet("still missing, attempt 5", List.of()));
        model.last().say("done");

        assertNull(guard.goal(), "超过驳回上限才认输");
        assertEquals(5, guard.falseClaims());
        assertEquals(1, guard.aborts());
    }

    @Test
    void anObjectiveIsJudgedByTheAuthoritativeHoldingNotTheReply() {
        AcceptanceGoal goal = aStockGoal();
        // 世界(权威持有量)只有 2 个铁锭;模型嘴上说有三个。
        nextVerdict.set(goal.predicate().evaluate(Observation.of(T0,
                Map.of("have", "Found 2 matching item(s) on player numen"))));
        guard.set(goal);

        ownerSays("hold 3 iron ingots");
        model.last().say("I have 3 iron ingots, done");

        assertSame(goal, guard.goal());
        assertEquals(1, guard.falseClaims());
        assertTrue(model.last().lastUser().contains("have 2/3"), model.last().lastUser());
    }
}
