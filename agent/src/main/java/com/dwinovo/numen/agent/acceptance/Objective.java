package com.dwinovo.numen.agent.acceptance;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * 机器可判的目标条件——验收的判据本体。模型可以声明它,但<b>判它的是代码</b>:
 * 只有这个谓词被权威世界状态满足,目标才算达成。
 *
 * <p>直接搬自 MineAI 的 {@code com.mineai.agent.Objective}(同名字段、同判据),去掉了
 * Minecraft 依赖,好让 Numen 的纯 JVM 大脑与靶场都能用。两种形状不能混:
 *
 * <ul>
 *   <li>{@link Type#HAVE_ITEM}:绝对持有("至少 3 个铁矿石")。</li>
 *   <li>{@link Type#GAIN_ITEM} / {@link Type#DELIVER_ITEM}:相对增量,对着目标受理那一刻
 *       的库存算("再弄 3 个""交出去 1 个")。</li>
 * </ul>
 *
 * <p>相对目标为什么要有基线:她一开始可能已经攥着两个铁。没有基线,"拿到 3 个"会把她
 * 原地不动判成达成。MineAI 在受理目标时把基线量下来({@code objectiveBaseline = countOf(...)}),
 * 这里同样由调用方在设定目标时量一次。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public record Objective(Type type, String itemId, int count) {

    public enum Type {
        NONE,
        HAVE_ITEM,
        GAIN_ITEM,
        DELIVER_ITEM
    }

    public static final Objective NONE = new Objective(Type.NONE, "", 0);

    public static Objective haveItem(String itemId, int count) {
        return new Objective(Type.HAVE_ITEM, itemId, Math.max(1, count));
    }

    /** "再拿 {@code count} 个",对着任务开始时的库存算。 */
    public static Objective gainItem(String itemId, int count) {
        return new Objective(Type.GAIN_ITEM, itemId, Math.max(1, count));
    }

    /** "交给委托方 {@code count} 个",相对增量。 */
    public static Objective deliverItem(String itemId, int count) {
        return new Objective(Type.DELIVER_ITEM, itemId, Math.max(1, count));
    }

    public boolean isSet() {
        return type != Type.NONE;
    }

    /** 判据依不依赖受理目标时的库存。 */
    public boolean isRelative() {
        return type == Type.GAIN_ITEM || type == Type.DELIVER_ITEM;
    }

    /**
     * 判据成没成立。
     *
     * @param have     当前权威持有量
     * @param baseline 受理目标那一刻量下的持有量
     */
    public boolean met(int have, int baseline) {
        return switch (type) {
            case NONE -> true;
            case HAVE_ITEM -> have >= count;
            case GAIN_ITEM, DELIVER_ITEM -> have - baseline >= count;
        };
    }

    public String describe() {
        return switch (type) {
            case HAVE_ITEM -> count + "x " + itemId + " in inventory";
            case GAIN_ITEM -> "gain " + count + "x " + itemId + " (relative to task start)";
            case DELIVER_ITEM -> "deliver " + count + "x " + itemId + " to the requester";
            case NONE -> "none";
        };
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("type", type.name());
        if (type != Type.NONE) {
            object.addProperty("item", itemId);
            object.addProperty("count", count);
        }
        return object;
    }

    public static Objective fromJson(JsonObject object) {
        if (object == null || !object.has("type")) {
            return NONE;
        }
        String type = object.get("type").getAsString();
        String item = object.has("item") ? object.get("item").getAsString() : "";
        int count = object.has("count") ? object.get("count").getAsInt() : 1;
        if (item.isBlank()) {
            return NONE;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "HAVE_ITEM" -> haveItem(item, count);
            case "GAIN_ITEM" -> gainItem(item, count);
            case "DELIVER_ITEM" -> deliverItem(item, count);
            default -> NONE;
        };
    }
}
