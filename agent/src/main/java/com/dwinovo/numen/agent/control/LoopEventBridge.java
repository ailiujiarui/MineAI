package com.dwinovo.numen.agent.control;

import com.dwinovo.numen.agent.loop.LoopEvent;

/**
 * 把现有循环内核的 {@link LoopEvent} 接进控制器——事件驱动那一半的桥。
 *
 * <p>用法:{@code loop.subscribe(bridge::on)}。内核照旧发它的事件,不感知控制器;桥把它们
 * 翻成 {@link ControlEvent} 送进 {@link AgentController#accept}。至于这些事件对现场意味着
 * 什么信号,由宿主在构造 {@code ControlState} 时决定——桥只搬运,不解释。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class LoopEventBridge {

    private final AgentController controller;

    public LoopEventBridge(AgentController controller) {
        this.controller = controller;
    }

    public void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.RunStarted e ->
                    controller.accept(ControlEvent.of("run.started", Long.toString(e.runId())));
            case LoopEvent.TurnStarted e ->
                    controller.accept(ControlEvent.of("turn.started", Long.toString(e.runId())));
            case LoopEvent.AssistantMessage e ->
                    controller.accept(ControlEvent.of("assistant", e.turn().hasToolCalls() ? "tools" : "final"));
            case LoopEvent.ToolFinished e ->
                    controller.accept(ControlEvent.of("tool.finished", e.call().name()));
            case LoopEvent.RunEnded e ->
                    controller.accept(ControlEvent.of("run.ended", e.end().getClass().getSimpleName()));
            case LoopEvent.TurnFailed e ->
                    controller.accept(ControlEvent.of("turn.failed", e.words()));
            case LoopEvent.Halted e ->
                    controller.accept(ControlEvent.of("halted", e.reason().name()));
            case LoopEvent.HoldChanged e ->
                    controller.accept(ControlEvent.of("hold.changed", String.valueOf(e.hold())));
            default -> { }
        }
    }
}
