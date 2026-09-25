package com.dwinovo.numen.agent.bt;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 行为树这一次 tick 的上下文:一个黑板 + 当前 tick 数 + 现场文本。
 *
 * <p>黑板让节点之间传状态(次数、目标坐标、上一次的结果);现场文本给边界节点(JEV/LLM)
 * 看。跨 tick 的状态放在节点自己字段里,黑板是给同一次执行里的协作节点用的。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class BtContext {

    private final Map<String, Object> board = new HashMap<>();
    private long tick;
    private Supplier<String> stateText = () -> "";

    /** 控制器每次 tick 开头调。 */
    public void beginTick(long tick, Supplier<String> stateText) {
        this.tick = tick;
        this.stateText = stateText == null ? () -> "" : stateText;
    }

    public long tick() {
        return tick;
    }

    /** 给边界节点看的一段现场(通常来自 {@code ControlState.render()})。 */
    public String stateText() {
        return stateText.get();
    }

    public Object get(String key) {
        return board.get(key);
    }

    public int getInt(String key, int fallback) {
        Object v = board.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public void put(String key, Object value) {
        board.put(key, value);
    }

    public void remove(String key) {
        board.remove(key);
    }

    public Map<String, Object> board() {
        return board;
    }

    /** 清空黑板(换行为时用)。 */
    public void reset() {
        board.clear();
        tick = 0;
    }
}
