package com.dwinovo.numen.agent.acceptance;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 靶场期允许模型做的动作——MineAI 的 {@code ToolRegistry} 搬到 Numen 的这一侧。
 *
 * <p>MineAI 的模型根本没有 {@code command} 这类万能口子,能做的只有登记过的那三十来个工具;
 * 白名单就是那份工具表本身。Numen 的模型手上握着 {@code command},一行 {@code /setblock} 就
 * 能把探针要看的方块摆出来,谓词立刻成立,而她一步没走。<b>验收的第一条红线不是"她能不能
 * 做到",而是"她能不能作弊"。</b>
 *
 * <p>因此出厂白名单做两件事:
 * <ul>
 *   <li><b>拿掉万能口子</b>:直接禁掉 {@code command} 工具,和 MineAI 不暴露它等价。</li>
 *   <li><b>按能力列白名单</b>:{@link #allowOnly} 只放行点名的那几个工具,这是最贴近
 *       MineAI {@code ToolRegistry} 的形状。</li>
 * </ul>
 * 另有一条命令根黑名单兜底:万一某次靶场要用 {@code command},改世界 / 给物品 / 传坐标的
 * 根名仍然一律拒绝。
 *
 * <p><b>这不是权限层。</b>它只活在一次验收里,拦的是"验收藏假",不是"她该不该改世界"。
 * 权限层的裁决照旧独立发生。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class Whitelist {

    /** Numen 唯一的万能口子。靶场默认拿掉它——MineAI 从来没有这个工具。 */
    public static final String COMMAND_TOOL = "command";

    /**
     * 万一放行了 {@code command},这些根名仍然一律拒绝:凡是不真干活就能满足探针的,都在这儿。
     *
     * <p>{@code execute} 也在列:它能包着 {@code run setblock} 绕进来,而探针用的是
     * {@code /execute if …},靶场自己发,不需要模型发。
     */
    private static final Set<String> MUTATING = Set.of(
            "setblock", "fill", "clone", "summon", "give", "item", "data", "tp", "teleport",
            "effect", "enchant", "gamemode", "kill", "clear", "loot", "xp", "experience",
            "time", "weather", "difficulty", "gamerule", "setworldspawn", "spreadplayers",
            "forceload", "scoreboard", "tag", "team", "place", "structure", "jigsaw",
            "worldborder", "damage", "attribute", "ride", "spectate", "execute", "function",
            "op", "deop", "kick", "ban", "pardon", "whitelist", "banlist", "reload");

    private final Set<String> allowedTools;
    private final Set<String> forbiddenTools;
    private final Set<String> allowedCommandRoots;
    private final Set<String> deniedCommandRoots;

    private Whitelist(Set<String> allowedTools, Set<String> forbiddenTools,
                      Set<String> allowedCommandRoots, Set<String> deniedCommandRoots) {
        this.allowedTools = Set.copyOf(allowedTools);
        this.forbiddenTools = Set.copyOf(forbiddenTools);
        this.allowedCommandRoots = Set.copyOf(allowedCommandRoots);
        this.deniedCommandRoots = Set.copyOf(deniedCommandRoots);
    }

    /** 出厂:所有工具放行,但拿掉 {@code command};命令根黑名单兜底。 */
    public static Whitelist harnessDefault() {
        return new Whitelist(Set.of(), Set.of(COMMAND_TOOL), Set.of(), MUTATING);
    }

    /**
     * 按能力列白名单——最贴近 MineAI {@code ToolRegistry} 的形状。列在这里的工具才放行,
     * 其余一律拒绝。
     */
    public static Whitelist allowOnly(Set<String> toolNames) {
        return new Whitelist(toolNames, Set.of(), Set.of(), Set.of());
    }

    /**
     * 自划一条线。
     *
     * @param allowedTools        工具名白名单;空 = 不按白名单收窄
     * @param allowedCommandRoots 命令根白名单;空 = 不按根名放行表拦
     * @param deniedCommandRoots  命令根黑名单;总能生效
     */
    public static Whitelist of(Set<String> allowedTools, Set<String> allowedCommandRoots,
                               Set<String> deniedCommandRoots) {
        return new Whitelist(allowedTools, Set.of(), allowedCommandRoots, deniedCommandRoots);
    }

    public Set<String> deniedCommandRoots() {
        return deniedCommandRoots;
    }

    /** 一次裁决。 */
    public Decision judge(LlmToolCall call) {
        if (call == null) {
            return Decision.allow();
        }
        String tool = call.name() == null ? "" : call.name();
        if (forbiddenTools.contains(tool)) {
            return Decision.deny("工具 '" + tool + "' 是万能口子,靶场里拿掉");
        }
        if (!allowedTools.isEmpty() && !allowedTools.contains(tool)) {
            return Decision.deny("工具 '" + tool + "' 不在这次靶场的白名单里");
        }
        if (!COMMAND_TOOL.equals(tool)) {
            return Decision.allow();
        }
        String root = commandRoot(call.arguments());
        if (root.isEmpty()) {
            return Decision.allow();
        }
        if (!allowedCommandRoots.isEmpty() && !allowedCommandRoots.contains(root)) {
            return Decision.deny("命令根 '" + root + "' 不在这次靶场的白名单里");
        }
        if (deniedCommandRoots.contains(root)) {
            return Decision.deny("命令根 '" + root + "' 能绕开探针,靶场里不用");
        }
        return Decision.allow();
    }

    /** 从 {@code command} 工具的参数里抠出命令根名。 */
    static String commandRoot(String arguments) {
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject args = parsed.getAsJsonObject();
            if (!args.has("command") || !args.get("command").isJsonPrimitive()) {
                return "";
            }
            String line = args.get("command").getAsString().strip();
            if (line.startsWith("/")) {
                line = line.substring(1).strip();
            }
            int space = line.indexOf(' ');
            return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("allowedTools", array(allowedTools));
        o.add("forbiddenTools", array(forbiddenTools));
        o.add("allowedCommandRoots", array(allowedCommandRoots));
        o.add("deniedCommandRoots", array(deniedCommandRoots));
        return o;
    }

    private static JsonArray array(Set<String> values) {
        JsonArray arr = new JsonArray();
        new LinkedHashSet<>(values).forEach(arr::add);
        return arr;
    }

    /** 一次裁决的结果。 */
    public record Decision(boolean allowed, String reason) {

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
