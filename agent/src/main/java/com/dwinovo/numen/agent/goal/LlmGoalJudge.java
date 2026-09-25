package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelRequest;

import java.util.List;
import java.util.function.Consumer;

/**
 * 默认判官:另开一次干净的模型调用。
 *
 * <p>不带对话历史、不带人设、不带工具,只看条件、身体事实和最近几句。用量经
 * {@link AgentLoop#consult} 照常进账(记在 {@link LoopEvent.Purpose#GOAL} 名下)。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class LlmGoalJudge implements GoalJudge {

    private final AgentLoop loop;

    public LlmGoalJudge(AgentLoop loop) {
        this.loop = loop;
    }

    @Override
    public void judge(GoalState goal, String facts, String since, CancelToken cancel, Consumer<Outcome> onDone) {
        ModelRequest request = new ModelRequest(
                List.of(new ConvoState.Msg.User(GoalPrompts.evaluatorQuery(goal, facts, since))),
                List.of(), GoalPrompts.evaluatorSystem());
        loop.consult(LoopEvent.Purpose.GOAL, request, cancel, outcome -> {
            switch (outcome) {
                case ModelOutcome.Failed failed -> onDone.accept(Outcome.failed(failed.words()));
                case ModelOutcome.Answered answered -> onDone.accept(
                        Outcome.of(GoalPrompts.readVerdict(answered.turn().content()), answered.usage().fresh()));
            }
        });
    }
}
