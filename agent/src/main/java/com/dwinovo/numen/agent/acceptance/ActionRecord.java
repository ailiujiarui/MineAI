package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

/**
 * 一条被记录下来的动作——验收台账的一行。
 *
 * <p>它不是"模型说她做了什么",是把 {@link com.dwinovo.numen.agent.loop.ToolPort} 外面
 * 那一层看到的东西原样记下:派了哪个工具、参数是什么、结果长什么样、成没成、有没有被
 * 靶场白名单拦下。报告靠它证明"跑过什么",而不是靠复盘模型的话。
 *
 * @param seq    同一份台账里的顺序号
 * @param callId 模型给这次调用铸的 id
 * @param tool   工具名
 * @param args   工具参数原文(合法 JSON 对象文本)
 * @param result 工具结果原文;被白名单拦下的是靶场写的失败信封
 * @param denied 是不是被靶场白名单拦下的(不是工具自己失败)
 * @param atMs   记录时刻
 */
public record ActionRecord(long seq, String callId, String tool, String args, String result,
                           boolean success, boolean denied, long atMs) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("callId", callId);
        o.addProperty("tool", tool);
        o.addProperty("args", args);
        o.addProperty("result", result);
        o.addProperty("success", success);
        o.addProperty("denied", denied);
        o.addProperty("at", atMs);
        return o;
    }
}
