package com.dwinovo.numen.agent.adapter;

import com.google.gson.JsonObject;

/**
 * 物品选择器:精确 id、前缀(尾部 {@code *})、或 {@code *}(全匹配)。
 *
 * <p>适配规则要能一次点一片(比如所有 {@code tacz:*} 枪),所以选择器比单个 id 宽松一点。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record ItemSelector(String pattern) {

    public ItemSelector {
        pattern = pattern == null ? "" : pattern.strip();
    }

    public static ItemSelector of(String pattern) {
        return new ItemSelector(pattern);
    }

    public boolean matches(String itemId) {
        if (pattern.isEmpty() || itemId == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return itemId.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(itemId);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("item", pattern);
        return o;
    }
}
