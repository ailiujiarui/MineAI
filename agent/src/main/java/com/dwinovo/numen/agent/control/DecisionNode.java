package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;
import com.dwinovo.numen.agent.decision.DecisionPort;
import com.dwinovo.numen.agent.decision.DecisionResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * JEV 判断叶:对现场问一组带类型的题,答案选一条分支跑。
 *
 * <p>这就是判断层接进行为树的地方——"该走哪条路""用哪种木板""这算不算进展"这类语义裁决,
 * 交给 JEV(便宜、有置信度、可门槛),不用把生成模型整个叫起来。题在节点第一次 tick 时发出,
 * 答案没回来之前回 {@link Status#RUNNING},不阻塞 tick。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class DecisionNode implements Node {

    private final DecisionPort port;
    private final JsonObject questions;
    private final String answerKey;
    private final Map<String, Node> branches;
    private final Node fallback;

    private CompletableFuture<DecisionResult> pending;
    private DecisionResult result;

    /**
     * @param questions 题键 → {@link com.dwinovo.numen.agent.decision.DecisionQuestion} 造的题
     * @param answerKey 要读哪道题的 {@code choice}
     * @param branches  选项 → 子树
     * @param fallback  没有匹配选项(或判断失败)时跑的子树;可空
     */
    public DecisionNode(DecisionPort port, JsonObject questions, String answerKey,
                        Map<String, Node> branches, Node fallback) {
        this.port = port;
        this.questions = questions;
        this.answerKey = answerKey;
        this.branches = Map.copyOf(branches);
        this.fallback = fallback;
    }

    /** 这次判断选了什么;还没判出来是 {@code null}。 */
    public String chosen() {
        return result == null ? null : result.choice(answerKey);
    }

    @Override
    public Status tick(BtContext ctx) {
        if (result == null) {
            if (pending == null) {
                pending = port.decide(ctx.stateText(), questions);
                pending.whenComplete((answer, error) ->
                        result = error == null && answer != null ? answer : DecisionResult.empty());
            }
            return Status.RUNNING;
        }
        Node child = branches.get(result.choice(answerKey));
        if (child == null) {
            child = fallback;
        }
        if (child == null) {
            return Status.FAILURE;
        }
        return child.tick(ctx);
    }

    @Override
    public void reset() {
        pending = null;
        result = null;
        branches.values().forEach(Node::reset);
        if (fallback != null) {
            fallback.reset();
        }
    }

    @Override
    public String describe() {
        return "decision(" + answerKey + ")";
    }
}
