package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一条探针输出的匹配规则——判定谓词的叶子。
 *
 * <p>谓词不是模型说的话,是靶场对世界的量测。每条探针跑一条 RCON 指令,拿回一串输出,
 * 再由这里的规则判"世界是不是长成目标要的样子"。三类够用:包含、全等、正则。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public sealed interface Expectation permits Expectation.Any, Expectation.Contains, Expectation.Exact, Expectation.Regex {

    /** 这串输出算不算命中。 */
    boolean matches(String output);

    /** 人读的一句话,写进报告和续跑提示。 */
    String describe();

    /** JSON 里的种类名,落盘用。 */
    String kind();

    JsonObject toJson();

    // ---- 四种规则 ----

    /**
     * 不计较输出,只要能拿到就算命中。给"谓词自己解析输出"的探针用
     * ({@link ObjectivePredicate} 要读数量,不是布尔命中)。
     */
    record Any() implements Expectation {
        public boolean matches(String output) {
            return true;
        }

        public String describe() {
            return "any output";
        }

        public String kind() {
            return "any";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind());
            return o;
        }
    }

    /** 输出里含某个子串。最常用:原版命令成功时回 "Test passed"。 */
    record Contains(String needle) implements Expectation {
        public boolean matches(String output) {
            return output != null && output.contains(needle);
        }

        public String describe() {
            return "contains '" + needle + "'";
        }

        public String kind() {
            return "contains";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind());
            o.addProperty("value", needle);
            return o;
        }
    }

    /** 输出正好等于这段文本。 */
    record Exact(String text) implements Expectation {
        public boolean matches(String output) {
            return output != null && output.strip().equals(text);
        }

        public String describe() {
            return "equals '" + text + "'";
        }

        public String kind() {
            return "exact";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind());
            o.addProperty("value", text);
            return o;
        }
    }

    /** 输出命中一条正则。 */
    record Regex(String pattern) implements Expectation {
        public boolean matches(String output) {
            return output != null && Pattern.compile(pattern).matcher(output).find();
        }

        public String describe() {
            return "matches /" + pattern + "/";
        }

        public String kind() {
            return "regex";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind());
            o.addProperty("value", pattern);
            return o;
        }
    }

    // ---- 工厂 ----

    static Expectation any() {
        return new Any();
    }

    static Expectation contains(String needle) {
        return new Contains(needle);
    }

    static Expectation exact(String text) {
        return new Exact(text);
    }

    static Expectation regex(String pattern) {
        return new Regex(pattern);
    }

    /**
     * 原版 {@code /execute if …} 成功时的回话。靶场最省事的探针形状:
     * {@code execute if block 0 -60 0 minecraft:diamond_block}。
     */
    static Expectation testPassed() {
        return new Contains("Test passed");
    }

    static Expectation fromJson(JsonObject o) {
        String kind = o.has("kind") ? o.get("kind").getAsString() : "contains";
        String value = o.has("value") ? o.get("value").getAsString() : "";
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "any" -> any();
            case "exact" -> exact(value);
            case "regex" -> regex(value);
            default -> contains(value);
        };
    }
}
