package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

/**
 * 一条世界量测:跑哪条 RCON 指令、输出要长成什么样。
 *
 * @param id          谓词内部认它用的名字,也是报告里的字段名
 * @param command     发到靶场服务端的这一行(不含前导 {@code /})
 * @param expectation 输出要命中的规则
 */
public record Probe(String id, String command, Expectation expectation) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("command", command);
        o.add("expect", expectation.toJson());
        return o;
    }

    public static Probe fromJson(JsonObject o) {
        String id = o.get("id").getAsString();
        String command = o.get("command").getAsString();
        Expectation expectation = o.has("expect")
                ? Expectation.fromJson(o.getAsJsonObject("expect"))
                : Expectation.testPassed();
        return new Probe(id, command, expectation);
    }
}
