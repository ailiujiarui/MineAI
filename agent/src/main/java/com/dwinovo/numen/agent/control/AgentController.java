package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.bt.BehaviorTree;
import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;
import com.dwinovo.numen.agent.decision.DecisionPort;
import com.dwinovo.numen.agent.utility.BehaviorOption;
import com.dwinovo.numen.agent.utility.ControlState;
import com.dwinovo.numen.agent.utility.UtilityArbiter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 事件驱动 + Utility + 行为树的控制器——把三个范式拧在一个唯一仲裁点上。
 *
 * <h2>它怎么转</h2>
 * 每个 tick,宿主把现场做成 {@link ControlState} 交给 {@link #tick}:
 * <ol>
 *   <li>仲裁器在候选行为里选分最高的({@link UtilityArbiter}),带迟滞防抖、支持抢占;</li>
 *   <li>选中的行为造一棵新树,{@link BtContext} 重置;</li>
 *   <li>tick 这棵树;SUCCESS/FAILURE 各发一条事件,然后空出来等下次选择。</li>
 * </ol>
 * 外部世界的事经 {@link #accept} 进来,由宿主并进 {@code ControlState} 的信号/事件里;
 * 需要判断的语义裁决走树里的 {@link DecisionNode}(JEV),需要拆计划的走 {@link LlmNode}(LLM)。
 * 于是 LLM 出不了内循环,而"谁拿身体"只有一个地方说了算。
 *
 * <p>纯 JVM,不碰 Minecraft。身体动作由宿主的行为树叶子和信号实现。
 */
public final class AgentController {

    private final String name;
    private final DecisionPort decisions;
    private final PlannerPort planner;
    private final List<BehaviorOption> options;
    private final UtilityArbiter arbiter;
    private final BtContext context = new BtContext();
    private final List<Consumer<ControlEvent>> listeners = new ArrayList<>();

    private BehaviorTree active;
    private String activeId;
    private long tick;

    public AgentController(String name, DecisionPort decisions, PlannerPort planner, List<BehaviorOption> options) {
        this(name, decisions, planner, new UtilityArbiter(), options);
    }

    public AgentController(String name, DecisionPort decisions, PlannerPort planner,
                           UtilityArbiter arbiter, List<BehaviorOption> options) {
        this.name = name;
        this.decisions = decisions;
        this.planner = planner;
        this.arbiter = arbiter;
        this.options = List.copyOf(options);
    }

    public BtContext context() {
        return context;
    }

    public DecisionPort decisions() {
        return decisions;
    }

    public PlannerPort planner() {
        return planner;
    }

    /** 当前跑着的行为;闲时 {@code null}。 */
    public String activeId() {
        return activeId;
    }

    public boolean busy() {
        return active != null;
    }

    public void subscribe(Consumer<ControlEvent> listener) {
        listeners.add(listener);
    }

    /** 事件驱动入口:外部事件落进来。宿主负责把同一件事并进下一次 {@link ControlState}。 */
    public void accept(ControlEvent event) {
        if (event != null) {
            emit(event);
        }
    }

    /** 推进一 tick。 */
    public void tick(ControlState state) {
        tick++;
        context.beginTick(tick, state == null ? () -> "" : state::render);
        ControlState snapshot = state == null ? ControlState.of(0L) : state;
        BehaviorOption chosen = arbiter.select(snapshot, options);
        if (chosen == null) {
            if (active != null) {
                clearActive("no runnable behavior");
            }
            return;
        }
        if (active == null || !chosen.id().equals(activeId)) {
            switchTo(chosen);
        }
        Node.Status status = active.tick(context);
        if (status == Node.Status.SUCCESS) {
            emit(ControlEvent.of(ControlEvent.SUCCESS, activeId));
            clearActive("success");
        } else if (status == Node.Status.FAILURE) {
            emit(ControlEvent.of(ControlEvent.FAILURE, activeId));
            clearActive("failure");
        }
    }

    private void switchTo(BehaviorOption option) {
        if (active != null) {
            emit(ControlEvent.of(ControlEvent.PREEMPTED, activeId));
        }
        active = option.freshTree();
        activeId = option.id();
        context.reset();
        emit(ControlEvent.of(ControlEvent.SELECTED, activeId));
    }

    private void clearActive(String why) {
        active = null;
        activeId = null;
        arbiter.clear();
        context.reset();
    }

    private void emit(ControlEvent event) {
        for (Consumer<ControlEvent> listener : List.copyOf(listeners)) {
            listener.accept(event);
        }
    }

    @Override
    public String toString() {
        return "AgentController(" + name + ", active=" + activeId + ", tick=" + tick + ")";
    }
}
