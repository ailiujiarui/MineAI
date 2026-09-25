package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

/**
 * 一次探针量测的结果——判定谓词的证据,也是 harness 报告里"世界当时长什么样"那一栏。
 *
 * @param matched 输出有没有命中期望
 */
public record ProbeResult(String id, String command, String output, boolean matched) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("command", command);
        o.addProperty("output", output == null ? "" : output);
        o.addProperty("matched", matched);
        return o;
    }
}
