package com.dwinovo.numen.api.agent.plan;

import com.dwinovo.numen.api.agent.script.ScriptCatalog;
import com.dwinovo.numen.api.agent.script.ScriptEngine;
import com.dwinovo.numen.api.agent.script.ScriptRun;
import com.dwinovo.numen.api.agent.script.ScriptType;
import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 六个原规划目标及库存约束,交给产品实际发布的 numen.inv.make 模块,由真实脚本引擎执行。
 * 配方、库存和工作站是有限夹具,不运行 Minecraft 或模型;夹具不替产品编写依赖规划或采集方案。
 * 当前模块不提供递归采集与依赖分配,相关目标的能力低分照实保留,不能用预写的行动步骤替它通过。
 */
@Tag("benchmark")
class PlanningBenchmark {

    private static final Gson GSON = new Gson();
    private static final String LOG = "minecraft:oak_log";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String STICK = "minecraft:stick";
    private static final String IRON = "minecraft:iron_ingot";
    private static final String STRING = "minecraft:string";
    private static final String HOOK = "minecraft:tripwire_hook";
    private static final String CROSSBOW = "minecraft:crossbow";
    private static final String PICKAXE = "minecraft:wooden_pickaxe";
    private static final String CHEST = "minecraft:chest";
    private static final String TABLE = "minecraft:crafting_table";
    private static final Recipe PLANK_RECIPE = recipe(PLANKS, 4, null, LOG, 1);
    private static final Recipe STICK_RECIPE = recipe(STICK, 4, null, PLANKS, 2);
    private static final Recipe PICKAXE_RECIPE = recipe(PICKAXE, 1, TABLE, PLANKS, 3, STICK, 2);
    private static final Recipe HOOK_RECIPE = recipe(HOOK, 2, TABLE, IRON, 1, STICK, 1, PLANKS, 1);
    private static final Recipe CROSSBOW_RECIPE = recipe(CROSSBOW, 1, TABLE, IRON, 1, STICK, 3, STRING, 2, HOOK, 1);
    private static final Recipe CHEST_RECIPE = recipe(CHEST, 1, TABLE, PLANKS, 8);

    private static Path output;
    private static Map<String, String> modules;
    private static Map<String, String> moduleHashes;

    @BeforeAll
    static void prepareReport() throws IOException, NoSuchAlgorithmException {
        String directory = System.getProperty("numen.eval.output");
        String moduleDirectory = System.getProperty("numen.eval.modules");
        if (directory == null || directory.isBlank() || moduleDirectory == null || moduleDirectory.isBlank()) {
            throw new IllegalStateException("benchmark requires numen.eval.output and numen.eval.modules");
        }
        output = Path.of(directory).resolve("planning.jsonl");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, "", StandardCharsets.UTF_8);
        modules = new LinkedHashMap<>();
        moduleHashes = new LinkedHashMap<>();
        for (String name : List.of("inv", "move")) {
            byte[] source = Files.readAllBytes(Path.of(moduleDirectory).resolve(name + ".lua"));
            modules.put("numen." + name, new String(source, StandardCharsets.UTF_8));
            moduleHashes.put("numen." + name,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        }
    }

    @Test
    void dependencyChain() throws IOException {
        Fixture fixture = new Fixture(Map.of(), Map.of(LOG, 2), Set.of(TABLE),
                PLANK_RECIPE, STICK_RECIPE, PICKAXE_RECIPE);
        Run run = run(PICKAXE, 1, fixture);
        boolean success = run.completed() && fixture.count(PICKAXE) == 1
                && fixture.gathered.equals(Map.of(LOG, 2))
                && fixture.count(PLANKS) == 3 && fixture.count(STICK) == 2;
        record("planning_dependency_chain", run, fixture, success,
                "Two logs yield eight planks; five planks become one wooden pickaxe and four sticks; "
                        + "the pickaxe consumes two sticks. A crafting table is preplaced.",
                "Expected one pickaxe, three planks and two sticks after gathering exactly two logs.");
    }

    @Test
    void existingStock() throws IOException {
        Fixture fixture = new Fixture(Map.of(LOG, 1), Map.of(LOG, 1), Set.of(), PLANK_RECIPE);
        Run run = run(PLANKS, 8, fixture);
        boolean success = run.completed() && fixture.count(PLANKS) == 8
                && fixture.count(LOG) == 0 && fixture.gathered.equals(Map.of(LOG, 1));
        record("planning_existing_stock", run, fixture, success,
                "Eight planks require two logs; one is already in inventory and one is available to gather.",
                "Expected eight planks and exactly one gathered log.");
    }

    @Test
    void sharedSiblingInventory() throws IOException {
        Fixture fixture = new Fixture(Map.of(IRON, 1, STICK, 3, STRING, 2, PLANKS, 1),
                Map.of(IRON, 1, STICK, 1), Set.of(TABLE), CROSSBOW_RECIPE, HOOK_RECIPE);
        Run run = run(CROSSBOW, 1, fixture);
        boolean success = run.completed() && fixture.count(CROSSBOW) == 1
                && fixture.gathered.equals(Map.of(IRON, 1, STICK, 1));
        record("planning_shared_sibling_inventory", run, fixture, success,
                "A crossbow and its hook together consume two iron ingots and four sticks. "
                        + "Only one ingot and three sticks are initially held; terminal ingredients can be gathered. "
                        + "This tests sibling branches of one goal, not an integrated multiple-goal planner.",
                "Expected one crossbow after gathering one iron ingot and one stick, with no double allocation.");
    }

    @Test
    void batchLeftovers() throws IOException {
        Fixture fixture = new Fixture(Map.of(IRON, 2, STRING, 2), Map.of(LOG, 1), Set.of(TABLE),
                CROSSBOW_RECIPE, HOOK_RECIPE, STICK_RECIPE, PLANK_RECIPE);
        Run run = run(CROSSBOW, 1, fixture);
        boolean success = run.completed() && fixture.count(CROSSBOW) == 1
                && fixture.gathered.equals(Map.of(LOG, 1))
                && fixture.count(PLANKS) == 1 && fixture.count(HOOK) == 1;
        record("planning_batch_leftovers", run, fixture, success,
                "One log yields four planks. Two planks yield four sticks, shared by the crossbow and hook; "
                        + "the hook uses a third plank and yields two hooks. Only one log exists in this fixture.",
                "Expected one crossbow, one spare plank and one spare hook using exactly one log.");
    }

    @Test
    void unavailableStationDoesNotFabricateOutput() throws IOException {
        Fixture fixture = new Fixture(Map.of(PLANKS, 8), Map.of(), Set.of(), CHEST_RECIPE);
        Run run = run(CHEST, 1, fixture);
        boolean success = !run.completed() && fixture.count(CHEST) == 0 && fixture.count(PLANKS) == 8
                && "not_found".equals(run.failureKind()) && run.failureMessage().startsWith("no crafting table");
        record("planning_station_unavailable", run, fixture, success,
                "Eight planks are held but no crafting table is available. The fixture cannot create a station. "
                        + "The expected behavior is an honest failure without consuming materials.",
                "Expected station failure, no chest and all eight planks preserved.");
    }

    @Test
    void ambiguousRecipeRequiresAChoice() throws IOException {
        Recipe birchChest = recipe(CHEST, 1, TABLE, "minecraft:birch_planks", 8);
        Fixture fixture = new Fixture(Map.of(), Map.of(), Set.of(TABLE), CHEST_RECIPE, birchChest);
        Run run = run(CHEST, 1, fixture);
        boolean success = !run.completed() && run.failureMessage().equals("ambiguous recipe")
                && fixture.trace.isEmpty();
        record("planning_ambiguous_recipe", run, fixture, success,
                "Two hand-written concrete chest recipe alternatives use oak or birch; no preference is supplied. "
                        + "The scenario requires unresolved ambiguity rather than choosing or gathering on its own.",
                "Expected an explicit ambiguous recipe result and no executed actions.");
    }

    private static Run run(String item, int count, Fixture fixture) {
        String code = "return numen.inv.make(" + GSON.toJson(item) + ", " + count + ")";
        ScriptRun script = ScriptEngine.IN_USE.start("planning-benchmark", code, fixture.catalog(), fixture.printed::add);
        long started = System.nanoTime();
        try {
            ScriptRun.Step step = script.start();
            int calls = 0;
            while (step instanceof ScriptRun.Call call) {
                if (++calls > 64) {
                    throw new IllegalStateException("bounded inventory fixture exceeded 64 API calls");
                }
                step = script.resume(fixture.reply(call));
            }
            if (!(step instanceof ScriptRun.Done done)) {
                throw new IllegalStateException("script returned neither an API call nor a verdict");
            }
            return new Run(done, calls, code, script.modules(), (System.nanoTime() - started) / 1_000L);
        } finally {
            script.close();
        }
    }

    private record Run(ScriptRun.Done done, int calls, String code, List<String> usedModules, long componentMicros) {
        boolean completed() {
            return done.ok();
        }

        String failureKind() {
            return done.failure() == null ? "" : String.valueOf(done.failure().get(ScriptRun.KIND));
        }

        String failureMessage() {
            return done.failure() == null ? "" : String.valueOf(done.failure().get(ScriptRun.MESSAGE));
        }
    }

    private static synchronized void record(String id, Run run, Fixture fixture, boolean success,
                                            String fixtureDescription, String criterion) throws IOException {
        JsonObject row = new JsonObject();
        row.addProperty("case_id", id);
        row.addProperty("evaluation_version", "bot-v1.2");
        row.addProperty("split", "dev");
        row.addProperty("layer", "planning");
        row.addProperty("success", success);
        row.addProperty("status", "ok");
        row.addProperty("reason", success ? criterion : criterion + " Observed: "
                + (run.done().ok() ? "final quantities differ" : run.done().error()));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("component_micros", run.componentMicros());
        metrics.addProperty("api_calls", run.calls());
        metrics.addProperty("action_attempts", fixture.trace.size());
        metrics.addProperty("model_calls", 0);
        row.add("metrics", metrics);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "synthetic_curated_vanilla_recipe_subset");
        evidence.addProperty("java_runtime", System.getProperty("java.runtime.version"));
        evidence.addProperty("fixture", fixtureDescription);
        evidence.addProperty("criterion", criterion);
        evidence.addProperty("entrypoint", "ScriptEngine.IN_USE + shipped numen.inv.make Lua module");
        evidence.addProperty("implementation_boundary", "Current product module; no fixture-written dependency planner");
        evidence.addProperty("comparison", "Same six goals and criteria, different production entrypoint; historical "
                + "GapCalculator/Compiler results are not equivalent module measurements");
        evidence.addProperty("timing_scope", "local interpreter, shipped module and fixture execution; not Minecraft or LLM latency");
        evidence.addProperty("script", run.code());
        evidence.addProperty("execution", run.completed() ? "SUCCESS" : "FAILURE");
        evidence.add("unresolved", GSON.toJsonTree(run.done().failure()));
        evidence.add("module_sha256", GSON.toJsonTree(moduleHashes));
        evidence.add("used_modules", GSON.toJsonTree(run.usedModules()));
        evidence.add("initial_inventory", GSON.toJsonTree(fixture.initial));
        evidence.add("available_resources", GSON.toJsonTree(fixture.resources));
        evidence.add("available_stations", GSON.toJsonTree(fixture.stations));
        evidence.add("planned_gather", JsonNull.INSTANCE);
        evidence.add("actual_gather", GSON.toJsonTree(fixture.gathered));
        evidence.add("final_inventory", GSON.toJsonTree(fixture.inventory));
        evidence.add("actions", GSON.toJsonTree(fixture.trace));
        evidence.add("api_trace", GSON.toJsonTree(fixture.apiTrace));
        evidence.add("printed", GSON.toJsonTree(fixture.printed));
        row.add("evidence", evidence);
        Files.writeString(output, row + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private record Recipe(String output, int outputCount, String station, Map<String, Integer> ingredients) {}

    private static Recipe recipe(String output, int count, String station, Object... ingredients) {
        Map<String, Integer> inputs = new LinkedHashMap<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            inputs.merge((String) ingredients[i], (Integer) ingredients[i + 1], Integer::sum);
        }
        return new Recipe(output, count, station, Map.copyOf(inputs));
    }

    /** 只提供原子 API 的世界事实及库存变更,不递归展开配方,不决定要采集什么。 */
    private static final class Fixture {
        private final Map<String, Integer> initial;
        private final Map<String, Integer> inventory;
        private final Map<String, Integer> resources;
        private final Set<String> stations;
        private final List<Recipe> recipes;
        private final Map<String, Integer> gathered = new LinkedHashMap<>();
        private final List<String> trace = new ArrayList<>();
        private final List<ScriptRun.Call> apiTrace = new ArrayList<>();
        private final List<String> printed = new ArrayList<>();
        private boolean tableOpen;

        Fixture(Map<String, Integer> initial, Map<String, Integer> resources, Set<String> stations, Recipe... recipes) {
            this.initial = Map.copyOf(initial);
            this.inventory = new LinkedHashMap<>(initial);
            this.resources = Map.copyOf(resources);
            this.stations = Set.copyOf(stations);
            this.recipes = List.of(recipes);
        }

        int count(String item) {
            return inventory.getOrDefault(item, 0);
        }

        private ScriptCatalog catalog() {
            ScriptCatalog.Function query = fn(1);
            return new ScriptCatalog(Map.of(
                    "numen.inv", Map.of("recipes", query, "count", query, "craft", fn(1, "count")),
                    "numen.gui", Map.of("view", fn(0), "close", fn(0)),
                    "numen.scan", Map.of("blocks", fn(1, "radius"), "block", query),
                    "numen.route", Map.of("plan", fn(1, "arrive", "range")),
                    "numen.move", Map.of("go", query),
                    "numen.use", Map.of("block", query),
                    "numen.status", Map.of("self", fn(0)),
                    "numen.build", Map.of("place", query)),
                    new ScriptCatalog.ModuleSource() {
                        @Override public String code(String name) { return modules.get(name); }
                        @Override public List<String> names() { return List.copyOf(modules.keySet()); }
                    }, Map.of());
        }

        private static ScriptCatalog.Function fn(int positions, String... options) {
            return new ScriptCatalog.Function(positions, Set.of(options), ScriptCatalog.Kind.VALUE,
                    new ScriptType.Simple("table"), null);
        }

        private ScriptRun.Result reply(ScriptRun.Call call) {
            apiTrace.add(call);
            return switch (call.function()) {
                case "numen.inv.recipes" -> ScriptRun.Result.ok(recipeFacts((String) call.args().getFirst()));
                case "numen.inv.count" -> ScriptRun.Result.ok(count((String) call.args().getFirst()));
                case "numen.inv.craft" -> craft((String) call.args().getFirst(),
                        ((Number) call.options().getOrDefault("count", 1)).intValue());
                case "numen.gui.view" -> ScriptRun.Result.ok(window());
                case "numen.gui.close" -> {
                    tableOpen = false;
                    yield ScriptRun.Result.ok(null);
                }
                case "numen.scan.blocks" -> ScriptRun.Result.ok(stations.contains(TABLE)
                        ? List.of(Map.of("nearest", Map.of("x", 1, "y", 64, "z", 0))) : List.of());
                case "numen.route.plan" -> ScriptRun.Result.ok(Map.of("fixture_route", true));
                case "numen.move.go" -> ScriptRun.Result.ok(null);
                case "numen.use.block" -> {
                    tableOpen = stations.contains(TABLE);
                    yield tableOpen ? ScriptRun.Result.ok(window()) : refuse("not_found", "station unavailable: " + TABLE);
                }
                case "numen.build.place" -> refuse("not_found", "station unavailable: fixture cannot construct a station");
                case "numen.status.self" -> ScriptRun.Result.ok(Map.of("pos", Map.of("x", 0, "y", 64, "z", 0)));
                case "numen.scan.block" -> ScriptRun.Result.ok(Map.of("is_air", true, "is_solid", true));
                default -> throw new IllegalStateException("fixture has no implementation for " + call.function());
            };
        }

        private Map<String, Object> window() {
            List<Map<String, String>> slots = new ArrayList<>();
            for (int i = 0; i < (tableOpen ? 9 : 4); i++) {
                slots.add(Map.of("side", "grid"));
            }
            return Map.of("slots", slots);
        }

        private List<Map<String, Object>> recipeFacts(String item) {
            List<Map<String, Object>> found = new ArrayList<>();
            for (int i = 0; i < recipes.size(); i++) {
                Recipe recipe = recipes.get(i);
                if (!recipe.output().equals(item)) continue;
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("id", "benchmark:recipe_" + i);
                facts.put("station", "crafting");
                facts.put("item", item);
                facts.put("makes", recipe.outputCount());
                facts.put("grid", recipe.station() == null ? 2 : 3);
                List<String> missing = new ArrayList<>();
                recipe.ingredients().forEach((input, needed) -> {
                    if (count(input) < needed) missing.add(needed + "x " + input + " (have " + count(input) + ")");
                });
                if (!missing.isEmpty()) facts.put("missing", missing);
                found.add(facts);
            }
            return found;
        }

        private ScriptRun.Result craft(String id, int wanted) {
            int index = Integer.parseInt(id.substring("benchmark:recipe_".length()));
            Recipe recipe = recipes.get(index);
            trace.add("craft " + wanted + " output(s) " + recipe.output());
            if (recipe.station() != null && !tableOpen) {
                return refuse("not_found", "station unavailable: " + recipe.station());
            }
            int batches = Math.ceilDiv(wanted, recipe.outputCount());
            for (var ingredient : recipe.ingredients().entrySet()) {
                batches = Math.min(batches, count(ingredient.getKey()) / ingredient.getValue());
            }
            if (batches < 1) return refuse("no_material", "craft shortfall: " + recipe.output());
            for (var ingredient : recipe.ingredients().entrySet()) {
                inventory.put(ingredient.getKey(), count(ingredient.getKey()) - ingredient.getValue() * batches);
            }
            int made = recipe.outputCount() * batches;
            inventory.merge(recipe.output(), made, Integer::sum);
            return ScriptRun.Result.ok(Map.of("crafted", made, "carrying", count(recipe.output())));
        }

        private static ScriptRun.Result refuse(String kind, String reason) {
            return ScriptRun.Result.failed(ScriptRun.failure(kind, reason, null, null, null));
        }
    }
}
