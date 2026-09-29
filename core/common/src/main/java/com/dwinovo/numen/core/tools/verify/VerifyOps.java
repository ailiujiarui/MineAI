package com.dwinovo.numen.core.tools.verify;

import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.tools.MachineConfigOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 确定性自验证工具 {@code verify} 的业务半。
 *
 * <p>今天"做完了没有"只有一套模型裁判({@code GoalSteward} + {@code LlmGoalJudge}),贵且仍然是句话说没说话。
 * 这里补一个便宜、确定的对照:拿模型的宣称去量权威状态——背包里数、世界里的方块、机器上的配置——回一句
 * 模型骗不了自己的判词。四个 kind 各量一种 claim:
 *
 * <ul>
 *   <li>{@code have} 背包里够不够——{@link PlayerInv#count} 一口数,口径和别处一致;</li>
 *   <li>{@code block} 某一格是不是某种方块——{@code level.getBlockState};</li>
 *   <li>{@code near} 半径内有没有这种方块——走 {@link BlockScanner#nearestBlock} 的同步小盒,只读已加载地形,
 *       半径有上限,便宜且有界;</li>
 *   <li>{@code machine} 机器某一档配置是不是某个值——读路径直接复用 {@link MachineConfigOps},不另抄一份反射。</li>
 * </ul>
 *
 * <p>回执是标准 {@link TaskResult}:顶层 {@code success} 说这一次检查本身有没有跑成(不成立也照样跑成),
 * {@code data} 里的 {@code verified} 才是宣称成不成立,连同 {@code expected}/{@code actual} 一起给出来。
 * 参数缺失或不合规一律 {@link IllegalArgumentException},由框架转成参数错;读不动权威状态是
 * {@code verified:false} 加原因,绝不把异常扔进游戏。
 */
public final class VerifyOps {

    static final int DEFAULT_NEAR_RADIUS = 16;
    static final int MIN_NEAR_RADIUS = 1;
    /** 同步小盒的半径上限:再大就该走 {@link com.dwinovo.numen.core.scan.BlockSearch} 的分片搜索,不是这里。 */
    static final int MAX_NEAR_RADIUS = 32;

    private final MachineConfigOps machines = new MachineConfigOps();

    /** 一次 verify 调用拆出来的字段;哪个 kind 要用哪些由下面的方法各自校验。 */
    public record Claim(String kind, String item, Integer count, String block,
                        Integer x, Integer y, Integer z, Integer radius,
                        String setting, String value, String side) {}

    /** 量一次宣称,回一份 {@link TaskResult} 的 JSON。参数不对抛 {@link IllegalArgumentException}。 */
    public String verify(Claim c, NumenPlayer self) {
        if (c.kind() == null || c.kind().isBlank()) {
            throw new IllegalArgumentException("give kind: one of have, block, near, machine");
        }
        return switch (c.kind().strip().toLowerCase(Locale.ROOT)) {
            case "have" -> have(c, self);
            case "block" -> block(c, self);
            case "near" -> near(c, self);
            case "machine" -> machine(c, self);
            default -> throw new IllegalArgumentException(
                    "unknown kind '" + c.kind() + "': pick one of have, block, near, machine");
        };
    }

    // ---- have ----

    private String have(Claim c, NumenPlayer self) {
        ResourceLocation id = requireId(c.item(), "item");
        Item item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown item: " + c.item()));
        int wanted = c.count() == null ? 1 : c.count();
        if (wanted < 1) {
            throw new IllegalArgumentException("count must be at least 1, got: " + wanted);
        }
        int actual = PlayerInv.count(self.getInventory(), item);
        boolean verified = actual >= wanted;
        String expected = wanted + " x " + id;
        String actualText = actual + " x " + id;
        String message = verified
                ? "Verified: you have " + actualText + " (>= " + wanted + ")."
                : "Not verified: you have " + actualText + ", short of " + expected + ".";
        return verdict(verified, expected, actualText, message);
    }

    // ---- block ----

    private String block(Claim c, NumenPlayer self) {
        Block block = requireBlock(c.block());
        BlockPos pos = requirePos(c, "block");
        if (!(self.level() instanceof ServerLevel level)) {
            return cannotCheck("block " + idOf(block) + " at " + coord(pos),
                    "block check needs a server level.");
        }
        BlockState state = level.getBlockState(pos);
        String wantedId = idOf(block);
        String actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean verified = state.getBlock() == block;
        String where = coord(pos);
        String expected = wantedId + " at " + where;
        String actual = actualId + " at " + where;
        String message = verified
                ? "Verified: " + wantedId + " is at " + where + "."
                : "Not verified: " + where + " is " + actualId + ", not " + wantedId + ".";
        return verdict(verified, expected, actual, message);
    }

    // ---- near ----

    private String near(Claim c, NumenPlayer self) {
        Block block = requireBlock(c.block());
        int radius = Math.clamp(c.radius() == null ? DEFAULT_NEAR_RADIUS : c.radius(),
                MIN_NEAR_RADIUS, MAX_NEAR_RADIUS);
        String name = idOf(block);
        String expected = name + " within " + radius + " blocks of you";
        if (!(self.level() instanceof ServerLevel level)) {
            return cannotCheck(expected, "near check needs a server level.");
        }
        BlockPos found = BlockScanner.nearestBlock(level, self.blockPosition(), self.getEyePosition(),
                radius, radius, radius, (p, state) -> state.is(block));
        boolean verified = found != null;
        String actual = verified
                ? name + " at " + coord(found)
                : "no " + name + " within " + radius + " blocks";
        String message = verified
                ? "Verified: " + actual + "."
                : "Not verified: no " + name + " within " + radius + " blocks of you.";
        return verdict(verified, expected, actual, message);
    }

    // ---- machine ----

    private String machine(Claim c, NumenPlayer self) {
        BlockPos pos = requirePos(c, "machine");
        if (c.setting() == null || c.setting().isBlank()) {
            throw new IllegalArgumentException("machine needs setting: the config name to check");
        }
        if (c.value() == null) {
            throw new IllegalArgumentException("machine needs value: the setting value to check for");
        }
        String coord = coord(pos);
        String expected = c.setting() + " = " + c.value();
        // 读路径复用 machine_config 的读取器(AE2 的 IConfigManager / 方块实体自己的配置),不另抄反射。
        String read = machines.machineConfig(pos.getX(), pos.getY(), pos.getZ(), c.side(), null, null, self);
        JsonObject root = JsonParser.parseString(read).getAsJsonObject();
        if (!root.get("success").getAsBoolean()) {
            return cannotCheck(expected, "could not read " + coord + ": " + messageOf(root));
        }
        JsonObject data = root.getAsJsonObject("data");
        List<Setting> settings = settingsIn(data);
        Setting found = null;
        for (Setting s : settings) {
            if (s.name().equalsIgnoreCase(c.setting().strip())) {
                found = s;
                break;
            }
        }
        String what = describe(data, coord);
        if (found == null) {
            String available = settings.isEmpty() ? "it exposes no settings"
                    : "available: " + String.join(", ", names(settings));
            return verdict(false, expected, "no setting named " + c.setting(),
                    "Not verified: " + what + " has no setting named '" + c.setting() + "' — " + available + ".");
        }
        boolean verified = found.value().equalsIgnoreCase(c.value().strip());
        String actual = found.name() + " = " + found.value();
        String message = verified
                ? "Verified: " + what + " has " + actual + "."
                : "Not verified: " + what + " has " + actual + ", not " + c.value() + ".";
        return verdict(verified, expected, actual, message);
    }

    // ---- machine read result ----

    /** 读取器报的一项配置;值取它的字符串形,比较时忽略大小写。 */
    private record Setting(String name, String value) {}

    /** 从读取器的 data 里收集配置项:方块实体在 {@code settings},part host 在各 {@code parts[].settings}。 */
    private static List<Setting> settingsIn(JsonObject data) {
        List<Setting> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        collect(data.getAsJsonArray("settings"), out);
        JsonArray parts = data.getAsJsonArray("parts");
        if (parts != null) {
            for (JsonElement element : parts) {
                collect(element.getAsJsonObject().getAsJsonArray("settings"), out);
            }
        }
        return out;
    }

    private static void collect(JsonArray array, List<Setting> out) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject setting = element.getAsJsonObject();
            JsonElement name = setting.get("name");
            JsonElement value = setting.get("value");
            if (name != null && value != null) {
                out.add(new Setting(name.getAsString(), value.getAsString()));
            }
        }
    }

    private static List<String> names(List<Setting> settings) {
        List<String> out = new ArrayList<>(settings.size());
        for (Setting s : settings) {
            if (!out.contains(s.name())) {
                out.add(s.name());
            }
        }
        return out;
    }

    private static String describe(JsonObject data, String coord) {
        JsonElement block = data == null ? null : data.get("block");
        return block == null ? "block at " + coord : block.getAsString() + " at " + coord;
    }

    private static String messageOf(JsonObject root) {
        JsonElement message = root.get("message");
        return message == null ? "" : message.getAsString();
    }

    // ---- results ----

    /** 检查跑成了:顶层成功,{@code verified} 说宣称本身成不成立。 */
    private static String verdict(boolean verified, String expected, String actual, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verified", verified);
        data.put("expected", expected);
        data.put("actual", actual);
        return TaskResult.ok(message, data).toJson();
    }

    /** 读不动权威状态:顶层失败,{@code verified} 为 false,附上原因。 */
    private static String cannotCheck(String expected, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verified", false);
        data.put("expected", expected);
        data.put("actual", "unknown");
        return TaskResult.fail(reason, data).toJson();
    }

    // ---- argument parsing ----

    private static BlockPos requirePos(Claim c, String kind) {
        if (c.x() == null || c.y() == null || c.z() == null) {
            throw new IllegalArgumentException(kind + " needs x, y, z: the block position to check");
        }
        return new BlockPos(c.x(), c.y(), c.z());
    }

    private static ResourceLocation requireId(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("give " + what + ": the id to check");
        }
        String s = raw.strip();
        ResourceLocation id = s.indexOf(':') < 0
                ? ResourceLocation.tryParse("minecraft:" + s)
                : ResourceLocation.tryParse(s);
        if (id == null) {
            throw new IllegalArgumentException("not a valid " + what + " id: " + raw);
        }
        return id;
    }

    private static Block requireBlock(String raw) {
        ResourceLocation id = requireId(raw, "block");
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null || block == Blocks.AIR) {
            throw new IllegalArgumentException("unknown block: " + raw);
        }
        return block;
    }

    private static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String coord(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
