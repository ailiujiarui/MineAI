package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具层外面的一层:把模型派的动作排成一列,逐条记进验收台账,并在派进工具层之前过一遍靶场白名单。
 *
 * <h2>为什么在"工具层外面"</h2>
 * 它实现的就是循环内核认识的那一个口 {@link ToolPort}——内核照旧调它,它转手交给真的派发器。
 * 于是工具本身({@code NumenTool}、{@code ToolRegistry}、{@code ToolDispatcher})一行都不用改,
 * 验收骨骼只是把端口包了一层。<b>这是整个改造最轻的切入点。</b>
 *
 * <h2>台账,不是自述</h2>
 * 每次调用"派了、结算了"各记一行,{@code success} 直接问
 * {@link ToolOutcome}(与展示层同一真源)。报告最后看的是这份台账,不是模型说她做了什么。
 *
 * <h2>白名单在这里落地</h2>
 * 被 {@link Whitelist} 拦下的调用<b>不进工具层</b>,当场以失败信封结算——模型看得见"这行不让做",
 * 却没有任何一行真的到了世界。这跟权限层不冲突:白名单只活在一次验收里,管的是"别作弊",
 * 权限层管的才是"该不该改世界"。
 *
 * <p>纯 JVM。回调都在内核线程上,台账不加锁。
 */
public final class ActionQueue implements ToolPort {

    private final ToolPort delegate;
    private final Whitelist whitelist;
    private final List<ActionRecord> ledger = new ArrayList<>();
    private long seq;

    public ActionQueue(ToolPort delegate, Whitelist whitelist) {
        this.delegate = delegate;
        this.whitelist = whitelist;
    }

    /** 台账快照。 */
    public List<ActionRecord> ledger() {
        return List.copyOf(ledger);
    }

    public void clearLedger() {
        ledger.clear();
        seq = 0;
    }

    @Override
    public void run(List<LlmToolCall> calls, Sink sink) {
        List<LlmToolCall> allowed = new ArrayList<>();
        List<String> denialReasons = new ArrayList<>();
        for (LlmToolCall call : calls) {
            Whitelist.Decision decision = whitelist.judge(call);
            if (decision.allowed()) {
                allowed.add(call);
                denialReasons.add(null);
            }
        }
        // 先按原顺序报"派了",被拦的立刻结算,再交真的派发器。
        for (LlmToolCall call : calls) {
            sink.started(call);
        }
        for (LlmToolCall call : calls) {
            Whitelist.Decision decision = whitelist.judge(call);
            if (!decision.allowed()) {
                String json = denialJson(decision.reason());
                record(call, json, false, true);
                sink.finished(call, json);
            }
        }
        if (allowed.isEmpty()) {
            sink.settled();
            return;
        }
        delegate.run(allowed, new Sink() {
            @Override
            public void started(LlmToolCall call) {
                // 已经按原顺序报过,不重复。
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                record(call, resultJson, !ToolOutcome.failed(resultJson), false);
                sink.finished(call, resultJson);
            }

            @Override
            public void settled() {
                sink.settled();
            }
        });
    }

    @Override
    public List<String> cancel(boolean stopBody) {
        return delegate.cancel(stopBody);
    }

    private void record(LlmToolCall call, String result, boolean success, boolean denied) {
        ledger.add(new ActionRecord(++seq, call.id(), call.name(), call.arguments(), result,
                success, denied, System.currentTimeMillis()));
    }

    /** 拦下的调用给模型的回执——与 {@code TaskResult.fail} 同一形状,展示层认得。 */
    private static String denialJson(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        o.addProperty("message", "acceptance whitelist: " + (reason == null ? "denied" : reason));
        return o.toString();
    }
}
