package com.dwinovo.numen.core.tools.routine;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.cli.CommandRunner;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code routine}:她自己攒下来的可执行技能库——名字、说明、按序执行的命令行,回放时和
 * {@code command} 工具走同一个入口。
 *
 * <p>这是 Voyager 式的技能:一段真的跑通过、能再跑一遍的行为序列,而不是一段说明文字。步骤里可以写
 * {@code {参数名}},回放时用 {@code arguments} 填进去,于是同一条 routine 换个坐标/方块还能再用。
 *
 * <p>回放按序:每一步的回执回来、成功了才走下一步;哪一步失败就在那里停下,回执点名是第几步、那一行和
 * 失败原因,绝不悄悄往下跑。跑完把每一步的结果一并交回。
 *
 * <p>库落在世界/服务端目录的一个文件里,见 {@link RoutineStore}。
 */
public final class RoutineTool implements NumenTool {

    public static final String NAME = "routine";

    static final String SAVE = "save";
    static final String LIST = "list";
    static final String GET = "get";
    static final String RUN = "run";
    static final String DELETE = "delete";

    /** 步骤里 {@code {arg}} 的样子。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Your own library of saved, working command routines. A routine is a named sequence of the very \
                command lines the `command` tool runs, replayed in order. A step may take arguments written \
                {name}; fill them when you run it, so one routine fits more than one spot.
                After a multi-step task finally works, `save` that exact sequence so you never have to work it out \
                again. Before hand-doing a familiar sequence, `list` your routines and `run` the matching one \
                instead of re-deriving it step by step.
                Actions:
                - save: store a routine: name, one-line description, steps (the exact lines that worked), and \
                args (the argument names your steps use).
                - list: every routine with its description and argument names. Start here.
                - get: one routine's full text and steps.
                - run: replay a routine's steps in order through the same entry point as `command`. It stops at the \
                first step that fails and says which step and why, and it also stops at a step that starts \
                background work (a task_id comes back instead of a finished result) — that step is not done yet, so \
                wait for its task_finished, then run the remaining steps. Otherwise it reports every step's result. \
                Pass the routine's arguments in `arguments` (name to value).
                - delete: drop a routine you no longer trust.
                A routine is only as good as its steps: keep them exact, and parameterize the parts that change with \
                {args} rather than saving a one-off copy per spot.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumField("What to do with the routine library.",
                SAVE, LIST, GET, RUN, DELETE));
        properties.put("name", field("string", "The routine's name: the handle for save, get, run and delete."));
        properties.put("description", field("string",
                "For save: the one line you will see in `list`, e.g. \"place a cobblestone then flip a lever\"."));
        properties.put("steps", stringArrayField("For save: the command lines, in order. Each is one line the "
                + "`command` tool would run, e.g. \"use block right 120 64 -35 --item minecraft:cobblestone\". "
                + "Write {name} where a value should be filled at run time."));
        properties.put("args", stringArrayField("For save: the argument names your steps use, each matching a "
                + "{name} placeholder, e.g. [\"x\", \"y\", \"z\"]."));
        properties.put("arguments", freeformObjectField("For run: the routine's arguments, name to value, e.g. "
                + "{\"x\": 120, \"y\": 64, \"z\": -35}."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = string(args, "action");
        if (action == null || action.isBlank()) {
            reply.accept(TaskResult.fail("give an action: save, list, get, run or delete.").toJson());
            return;
        }
        switch (action) {
            case SAVE -> save(companion, args, reply);
            case LIST -> list(companion, reply);
            case GET -> get(companion, args, reply);
            case RUN -> run(companion, toolCallId, args, reply);
            case DELETE -> delete(companion, args, reply);
            default -> reply.accept(TaskResult.fail("unknown action '" + action
                    + "'; the actions are save, list, get, run, delete.").toJson());
        }
    }

    // ---- save / list / get / delete ----

    private static void save(NumenPlayer her, JsonObject args, Consumer<String> reply) {
        String name = string(args, "name");
        if (name == null || name.isBlank()) {
            reply.accept(TaskResult.fail("give the routine a name to save it under.").toJson());
            return;
        }
        List<String> steps = strings(args, "steps");
        if (steps.isEmpty()) {
            reply.accept(TaskResult.fail("routine '" + name + "' has no steps; give the command lines to replay "
                    + "in `steps`.").toJson());
            return;
        }
        List<String> argNames = strings(args, "args");
        String description = string(args, "description");
        try {
            RoutineStore.save(her.getServer(),
                    new RoutineStore.Routine(name, description == null ? "" : description, steps, argNames));
        } catch (RuntimeException unwritable) {
            reply.accept(TaskResult.fail(unwritable.getMessage()).toJson());
            return;
        }
        reply.accept(TaskResult.ok("saved routine '" + name + "' with " + steps.size() + " step(s)"
                + (argNames.isEmpty() ? "" : " and argument(s) " + String.join(", ", argNames)) + ".").toJson());
    }

    private static void list(NumenPlayer her, Consumer<String> reply) {
        List<RoutineStore.Routine> routines = RoutineStore.list(her.getServer());
        if (routines.isEmpty()) {
            reply.accept(TaskResult.ok("no routines saved yet. After a multi-step task works, `save` it.").toJson());
            return;
        }
        StringBuilder sb = new StringBuilder(routines.size() == 1 ? "1 routine:" : routines.size() + " routines:");
        for (RoutineStore.Routine routine : routines) {
            sb.append("\n- ").append(routine.name()).append(": ").append(routine.description());
            if (!routine.args().isEmpty()) {
                sb.append(" (args: ").append(String.join(", ", routine.args())).append(')');
            }
        }
        reply.accept(TaskResult.ok(sb.toString()).toJson());
    }

    private static void get(NumenPlayer her, JsonObject args, Consumer<String> reply) {
        String name = string(args, "name");
        Optional<RoutineStore.Routine> found = RoutineStore.find(her.getServer(), name);
        if (found.isEmpty()) {
            reply.accept(TaskResult.fail("no routine named '" + name + "'. " + known(her.getServer())).toJson());
            return;
        }
        RoutineStore.Routine routine = found.get();
        StringBuilder sb = new StringBuilder("routine '").append(routine.name()).append("': ")
                .append(routine.description());
        if (!routine.args().isEmpty()) {
            sb.append("\nargs: ").append(String.join(", ", routine.args()));
        }
        sb.append("\nsteps:");
        for (int i = 0; i < routine.steps().size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(routine.steps().get(i));
        }
        reply.accept(TaskResult.ok(sb.toString()).toJson());
    }

    private static void delete(NumenPlayer her, JsonObject args, Consumer<String> reply) {
        String name = string(args, "name");
        if (name == null || name.isBlank()) {
            reply.accept(TaskResult.fail("give the name of the routine to delete.").toJson());
            return;
        }
        boolean removed;
        try {
            removed = RoutineStore.delete(her.getServer(), name);
        } catch (RuntimeException unwritable) {
            reply.accept(TaskResult.fail(unwritable.getMessage()).toJson());
            return;
        }
        if (!removed) {
            reply.accept(TaskResult.fail("no routine named '" + name + "'. " + known(her.getServer())).toJson());
            return;
        }
        reply.accept(TaskResult.ok("deleted routine '" + name + "'.").toJson());
    }

    // ---- run ----

    private static void run(NumenPlayer her, String callId, JsonObject args, Consumer<String> reply) {
        String name = string(args, "name");
        if (name == null || name.isBlank()) {
            reply.accept(TaskResult.fail("give the name of the routine to run.").toJson());
            return;
        }
        Optional<RoutineStore.Routine> found = RoutineStore.find(her.getServer(), name);
        if (found.isEmpty()) {
            reply.accept(TaskResult.fail("no routine named '" + name + "'. " + known(her.getServer())).toJson());
            return;
        }
        RoutineStore.Routine routine = found.get();
        Map<String, String> values;
        try {
            values = resolve(routine, object(args, "arguments"));
        } catch (IllegalArgumentException bad) {
            reply.accept(TaskResult.fail(bad.getMessage()).toJson());
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String step : routine.steps()) {
            lines.add(substitute(step, values));
        }
        runStep(her, callId, routine.name(), lines, 0, new ArrayList<>(), reply);
    }

    /**
     * 一步接一步:这一步的回执回来了、成功了,才派下一步。任一步失败就在这里收场,回执点名是第几步、哪一行、为什么;
     * 派下一件后台活(回执带 task_id)也在这里停住——受理不是做完,继续下一步就是抢跑。全部跑完把每一步的结果一并交回。
     * 调用 id 按步派生,每步的活各自认得清,不撞在一起。
     */
    private static void runStep(NumenPlayer her, String callId, String routineName, List<String> lines, int index,
                                List<String> reports, Consumer<String> reply) {
        if (index >= lines.size()) {
            reply.accept(TaskResult.ok("routine '" + routineName + "' finished; all " + lines.size()
                    + " step(s) ran:\n" + String.join("\n", reports)).toJson());
            return;
        }
        String line = lines.get(index);
        int number = index + 1;
        CommandRunner.run(her, callId + "-" + number, line, resultJson -> {
            boolean ok = succeeded(resultJson);
            String message = messageOf(resultJson);
            reports.add(number + ". " + line + " -> " + (ok ? "ok" : "FAILED") + ": " + message);
            if (!ok) {
                reply.accept(TaskResult.fail("routine '" + routineName + "' failed at step " + number + " of "
                        + lines.size() + ": `" + line + "` - " + message + "\n"
                        + String.join("\n", reports)).toJson());
                return;
            }
            // 这一步派下了一件后台活:受理回执不是"做完"。routing 不会等 task_finished,继续下一步就是抢跑,
            // 所以在这里停住,把剩下的步骤原样留着,等她的 task_finished 再来收拾局面。
            String background = TaskDispatch.runningTaskOf(resultJson);
            if (background != null) {
                reply.accept(TaskResult.ok("routine '" + routineName + "' paused at step " + number + " of "
                        + lines.size() + ": `" + line + "` started background task " + background
                        + ", which has not finished. Do not run the next step until its task_finished arrives; "
                        + "then run the remaining steps.\n" + String.join("\n", reports)).toJson());
                return;
            }
            runStep(her, callId, routineName, lines, number, reports, reply);
        });
    }

    /**
     * 把这次给的参数值配上 routine 声明的参数名:声明了没给值、给了没声明的名、步骤用了没声明的 {@code {name}}——
     * 三种都当场说清并列出它真正接受哪些,不带着半个替换往下跑。
     */
    private static Map<String, String> resolve(RoutineStore.Routine routine, JsonObject values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : routine.args()) {
            JsonElement value = values.get(name);
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("routine '" + routine.name() + "' needs a value for argument '"
                        + name + "'; it takes: " + orNone(routine.args()));
            }
            out.put(name, value.getAsString());
        }
        for (String given : values.keySet()) {
            if (!routine.args().contains(given)) {
                throw new IllegalArgumentException("routine '" + routine.name() + "' has no argument '" + given
                        + "'; it takes: " + orNone(routine.args()));
            }
        }
        for (String step : routine.steps()) {
            Matcher matcher = PLACEHOLDER.matcher(step);
            while (matcher.find()) {
                String token = matcher.group(1);
                if (!routine.args().contains(token)) {
                    throw new IllegalArgumentException("routine '" + routine.name() + "' step `" + step
                            + "` uses {" + token + "} which is not a declared argument; it takes: "
                            + orNone(routine.args()));
                }
            }
        }
        return out;
    }

    /** 把步骤里的 {@code {name}} 换成这次给的值;声明的参数都已校验过有值。 */
    private static String substitute(String step, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(step);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ---- 小工具 ----

    /** 没有这条 routine 时点名库里现有哪些,好让她改名字或先 save。 */
    private static String known(net.minecraft.server.MinecraftServer server) {
        List<RoutineStore.Routine> routines = RoutineStore.list(server);
        if (routines.isEmpty()) {
            return "there are no routines saved yet.";
        }
        List<String> names = new ArrayList<>();
        for (RoutineStore.Routine routine : routines) {
            names.add(routine.name());
        }
        return "there are: " + String.join(", ", names) + ".";
    }

    private static String orNone(List<String> names) {
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private static boolean succeeded(String resultJson) {
        try {
            JsonObject object = JsonParser.parseString(resultJson).getAsJsonObject();
            return !object.has("success") || object.get("success").getAsBoolean();
        } catch (RuntimeException notJson) {
            return true;
        }
    }

    private static String messageOf(String resultJson) {
        try {
            JsonObject object = JsonParser.parseString(resultJson).getAsJsonObject();
            JsonElement message = object.get("message");
            return message == null ? resultJson : message.getAsString();
        } catch (RuntimeException notJson) {
            return resultJson;
        }
    }

    private static String string(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static List<String> strings(JsonObject args, String key) {
        JsonElement value = args.get(key);
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

    private static JsonObject object(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static Map<String, Object> field(String type, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("description", description);
        return field;
    }

    private static Map<String, Object> enumField(String description, String... values) {
        Map<String, Object> field = field("string", description);
        field.put("enum", List.of(values));
        return field;
    }

    private static Map<String, Object> stringArrayField(String description) {
        Map<String, Object> field = field("array", description);
        field.put("items", field("string", "A command line."));
        return field;
    }

    private static Map<String, Object> freeformObjectField(String description) {
        Map<String, Object> field = field("object", description);
        field.put("additionalProperties", field("string", "The argument's value."));
        return field;
    }
}
