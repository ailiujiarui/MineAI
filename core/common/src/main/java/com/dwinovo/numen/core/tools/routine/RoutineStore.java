package com.dwinovo.numen.core.tools.routine;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可执行技能库:一行一条命令、已经被验证过能跑通的行为序列,落在 {@code <server>/numen/routines.json}。
 *
 * <p>一条 routine 就是 {@code {name, description, steps, args}}:名字、一句话说明、按序执行的命令行,
 * 以及可被步骤用 {@code {arg}} 引用的参数名。步骤写的正是 {@code command} 工具会执行的那些行
 * (第 1 层的 {@code <组> <动作> …},或行首 {@code /} 的原生指令),所以一条 routine 是一条
 * 可以直接回放的真实操作,而不是一段说明文字。
 *
 * <p>库是一个文件,整库读、整库写。坏文件、坏条目都只影响它自己:读不动的整库当空,坏条目跳过——
 * 一个字节写错不该让"回放"这件事整个炸掉。写盘失败才向调用方抛,由工具回一句干净的失败。
 */
public final class RoutineStore {

    /** 一条 routine:名字、说明、按序的命令行、可被 {@code {arg}} 引用的参数名。 */
    public record Routine(String name, String description, List<String> steps, List<String> args) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RoutineStore() {}

    /** 库文件:{@code <server>/numen/routines.json}(目录写时按需建)。 */
    public static Path file(MinecraftServer server) {
        return server.getServerDirectory().resolve("numen").resolve("routines.json");
    }

    /**
     * 全部 routine,按名字排序。文件不在、读不动、格式坏都当空表,坏条目单条跳过——
     * 库坏了不该让工具炸掉。
     */
    public static List<Routine> list(MinecraftServer server) {
        Path path = file(server);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException unreadable) {
            Constants.LOG.warn("[numen-routine] 读不出 {}:{}", path, unreadable.getMessage());
            return List.of();
        }
        List<Routine> out = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    Routine routine = read(element);
                    if (routine != null) {
                        out.add(routine);
                    }
                }
            }
        } catch (RuntimeException malformed) {
            Constants.LOG.warn("[numen-routine] {} 不是合法的 JSON,按空库处理:{}", path, malformed.getMessage());
            return List.of();
        }
        out.sort(Comparator.comparing(Routine::name));
        return out;
    }

    /** 按名字找一条;没有就是空。 */
    public static Optional<Routine> find(MinecraftServer server, String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (Routine routine : list(server)) {
            if (routine.name().equals(name)) {
                return Optional.of(routine);
            }
        }
        return Optional.empty();
    }

    /** 存一条(同名覆盖),整库写回。写不出去抛异常,交给工具回话。 */
    public static void save(MinecraftServer server, Routine routine) {
        Map<String, Routine> byName = new LinkedHashMap<>();
        for (Routine existing : list(server)) {
            byName.put(existing.name(), existing);
        }
        byName.put(routine.name(), routine);
        write(server, sorted(byName));
    }

    /** 删一条;返回是否删到了。 */
    public static boolean delete(MinecraftServer server, String name) {
        Map<String, Routine> byName = new LinkedHashMap<>();
        for (Routine existing : list(server)) {
            byName.put(existing.name(), existing);
        }
        if (byName.remove(name) == null) {
            return false;
        }
        write(server, sorted(byName));
        return true;
    }

    private static List<Routine> sorted(Map<String, Routine> byName) {
        List<Routine> ordered = new ArrayList<>(byName.values());
        ordered.sort(Comparator.comparing(Routine::name));
        return ordered;
    }

    private static void write(MinecraftServer server, List<Routine> routines) {
        JsonArray array = new JsonArray();
        for (Routine routine : routines) {
            JsonObject object = new JsonObject();
            object.addProperty("name", routine.name());
            object.addProperty("description", routine.description());
            JsonArray steps = new JsonArray();
            routine.steps().forEach(steps::add);
            object.add("steps", steps);
            JsonArray args = new JsonArray();
            routine.args().forEach(args::add);
            object.add("args", args);
            array.add(object);
        }
        Path path = file(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(array));
        } catch (IOException unwritable) {
            throw new IllegalStateException("cannot write the routine library to " + path + ": "
                    + unwritable.getMessage(), unwritable);
        }
    }

    /** 读一条;缺名字或缺步骤的坏条目返回 null,单条跳过。 */
    private static Routine read(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String name = string(object, "name");
        if (name == null || name.isBlank()) {
            return null;
        }
        List<String> steps = strings(object, "steps");
        if (steps.isEmpty()) {
            return null;
        }
        String description = string(object, "description");
        return new Routine(name, description == null ? "" : description, steps, strings(object, "args"));
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static List<String> strings(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return out;
    }
}
