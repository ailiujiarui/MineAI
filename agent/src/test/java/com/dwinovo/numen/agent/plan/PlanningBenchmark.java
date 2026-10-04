package com.dwinovo.numen.agent.plan;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工挑选的 dev 规划样例:真实 GapCalculator/Compiler + 有限资源的库存夹具。
 * 配方是手写的原版配方子集;这里不运行 Minecraft、模型、挖掘、寻路或时间流逝。
 * 业务失败写进 JSONL,不让 JUnit 提前中止基线;文件/夹具等基础设施异常仍然抛出。
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

    @Test
    void dependencyChain() throws IOException {
        Fixture fixture = new Fixture(Map.of(), Map.of(LOG, 2), Set.of(TABLE));
        Run run = run(GoalTask.obtain(PICKAXE, 1), fixture,
                book(PLANK_RECIPE, STICK_RECIPE, PICKAXE_RECIPE));
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
        Fixture fixture = new Fixture(Map.of(LOG, 1), Map.of(LOG, 1), Set.of());
        Run run = run(GoalTask.obtain(PLANKS, 8), fixture, book(PLANK_RECIPE));
        boolean success = run.completed() && fixture.count(PLANKS) == 8
                && fixture.count(LOG) == 0 && fixture.gathered.equals(Map.of(LOG, 1));
        record("planning_existing_stock", run, fixture, success,
                "Eight planks require two logs; one is already in inventory and one is available to gather.",
                "Expected eight planks and exactly one gathered log.");
    }

    @Test
    void sharedSiblingInventory() throws IOException {
        Fixture fixture = new Fixture(Map.of(IRON, 1, STICK, 3, STRING, 2, PLANKS, 1),
                Map.of(IRON, 1, STICK, 1), Set.of(TABLE));
        Run run = run(GoalTask.obtain(CROSSBOW, 1), fixture, book(CROSSBOW_RECIPE, HOOK_RECIPE));
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
        Fixture fixture = new Fixture(Map.of(IRON, 2, STRING, 2), Map.of(LOG, 1), Set.of(TABLE));
        Run run = run(GoalTask.obtain(CROSSBOW, 1), fixture,
                book(CROSSBOW_RECIPE, HOOK_RECIPE, STICK_RECIPE, PLANK_RECIPE));
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
        Fixture fixture = new Fixture(Map.of(PLANKS, 8), Map.of(), Set.of());
        Run run = run(GoalTask.obtain(CHEST, 1), fixture, book(CHEST_RECIPE));
        boolean success = run.execution() == Node.Status.FAILURE && fixture.count(CHEST) == 0
                && fixture.count(PLANKS) == 8 && fixture.failure.startsWith("station unavailable:");
        record("planning_station_unavailable", run, fixture, success,
                "Eight planks are held but no crafting table is available. The fixture cannot create a station. "
                        + "The expected behavior is an honest failure without consuming materials.",
                "Expected station failure, no chest and all eight planks preserved.");
    }

    @Test
    void ambiguousRecipeRequiresAChoice() throws IOException {
        Recipe birchChest = recipe(CHEST, 1, TABLE, "minecraft:birch_planks", 8);
        Fixture fixture = new Fixture(Map.of(), Map.of(), Set.of(TABLE));
        Run run = run(GoalTask.obtain(CHEST, 1), fixture, book(CHEST_RECIPE, birchChest));
        boolean success = !run.compiled().ok() && run.compiled().unmet() != null
                && run.compiled().unmet().reason().equals("ambiguous recipe")
                && fixture.trace.isEmpty();
        record("planning_ambiguous_recipe", run, fixture, success,
                "Two hand-written concrete chest recipe alternatives use oak or birch; no preference is supplied. "
                        + "The library contract returns unresolved rather than guessing.",
                "Expected an explicit ambiguous recipe result and no executed actions.");
    }

    private static Run run(GoalTask goal, Fixture fixture, RecipeBook book) {
        long started = System.nanoTime();
        Missing missing = GapCalculator.missing(goal, Counts.of(fixture.initial), book);
        Map<String, Integer> plannedGather = new LinkedHashMap<>();
        collectGather(missing, plannedGather);
        Compiled compiled = Compiler.compile(goal, Counts.of(fixture.initial), book, fixture);
        Node.Status execution = null;
        int ticks = 0;
        if (compiled.ok()) {
            BtContext context = new BtContext();
            do {
                context.beginTick(++ticks, () -> "bounded inventory fixture");
                execution = compiled.tree().tick(context);
            } while (execution == Node.Status.RUNNING && ticks < 16);
            if (execution == Node.Status.RUNNING) {
                throw new IllegalStateException("synchronous fixture unexpectedly exceeded 16 ticks");
            }
        }
        return new Run(compiled, execution, ticks, plannedGather, (System.nanoTime() - started) / 1_000L);
    }

    private static void collectGather(Missing missing, Map<String, Integer> totals) {
        if (missing instanceof Missing.Gather gather) {
            totals.merge(gather.item(), gather.count(), Integer::sum);
        } else if (missing instanceof Missing.Craft craft) {
            craft.inputs().forEach(input -> collectGather(input, totals));
        }
    }

    private record Run(Compiled compiled, Node.Status execution, int ticks,
                       Map<String, Integer> plannedGather, long componentMicros) {
        boolean completed() {
            return compiled.ok() && execution == Node.Status.SUCCESS;
        }
    }

    private static synchronized void record(String id, Run run, Fixture fixture, boolean success,
                                            String fixtureDescription, String criterion) throws IOException {
        String output = System.getProperty("numen.eval.output");
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("benchmark requires -Dnumen.eval.output=<directory>");
        }
        JsonObject row = new JsonObject();
        row.addProperty("case_id", id);
        row.addProperty("evaluation_version", "bot-v1.2");
        row.addProperty("split", "dev");
        row.addProperty("layer", "planning");
        row.addProperty("success", success);
        row.addProperty("status", "ok");
        row.addProperty("reason", success ? criterion : criterion + " Observed: "
                + (fixture.failure.isEmpty() ? "final quantities or unresolved outcome differ" : fixture.failure));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("component_micros", run.componentMicros());
        metrics.addProperty("fixture_ticks", run.ticks());
        metrics.addProperty("action_attempts", fixture.trace.size());
        metrics.addProperty("model_calls", 0);
        row.add("metrics", metrics);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "synthetic_curated_vanilla_recipe_subset");
        evidence.addProperty("java_runtime", System.getProperty("java.runtime.version"));
        evidence.addProperty("fixture", fixtureDescription);
        evidence.addProperty("criterion", criterion);
        evidence.addProperty("entrypoint", "GapCalculator.missing + Compiler.compile + BehaviorTree.tick");
        evidence.addProperty("timing_scope", "local component and fixture execution; not Minecraft or LLM latency");
        evidence.addProperty("compiled_ok", run.compiled().ok());
        evidence.addProperty("execution", run.execution() == null ? "not_executed" : run.execution().name());
        if (run.compiled().unmet() != null) {
            evidence.add("unresolved", GSON.toJsonTree(run.compiled().unmet()));
        }
        evidence.add("initial_inventory", GSON.toJsonTree(fixture.initial));
        evidence.add("available_resources", GSON.toJsonTree(fixture.initialResources));
        evidence.add("available_stations", GSON.toJsonTree(fixture.stations));
        evidence.add("planned_gather", GSON.toJsonTree(run.plannedGather()));
        evidence.add("actual_gather", GSON.toJsonTree(fixture.gathered));
        evidence.add("final_inventory", GSON.toJsonTree(fixture.inventory));
        evidence.add("actions", GSON.toJsonTree(fixture.trace));
        row.add("evidence", evidence);
        Path file = Path.of(output).resolve("planning.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, row + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Recipe recipe(String output, int count, String station, Object... ingredients) {
        List<ItemStack> inputs = new ArrayList<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            inputs.add(ItemStack.of((String) ingredients[i], (Integer) ingredients[i + 1]));
        }
        return new Recipe(output, count, inputs, station);
    }

    private static RecipeBook book(Recipe... recipes) {
        Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            byOutput.computeIfAbsent(recipe.output(), key -> new ArrayList<>()).add(recipe);
        }
        return item -> byOutput.getOrDefault(item, List.of());
    }

    /** 采集只从有限资源中转移;合成先查工作站和全部投入,再原子地扣料、入账。 */
    private static final class Fixture implements Actions {
        private final Map<String, Integer> initial;
        private final Map<String, Integer> initialResources;
        private final Map<String, Integer> inventory;
        private final Map<String, Integer> resources;
        private final Set<String> stations;
        private final Map<String, Integer> gathered = new LinkedHashMap<>();
        private final List<String> trace = new ArrayList<>();
        private String failure = "";

        Fixture(Map<String, Integer> initial, Map<String, Integer> resources, Set<String> stations) {
            this.initial = Map.copyOf(initial);
            this.initialResources = Map.copyOf(resources);
            this.inventory = new LinkedHashMap<>(initial);
            this.resources = new LinkedHashMap<>(resources);
            this.stations = Set.copyOf(stations);
        }

        int count(String item) {
            return inventory.getOrDefault(item, 0);
        }

        private Node.Status refuse(String reason) {
            failure = reason;
            trace.add("refused: " + reason);
            return Node.Status.FAILURE;
        }

        @Override
        public Node gather(String item, int count) {
            return new ActionNode("gather:" + item, context -> {
                trace.add("gather " + count + " " + item);
                int available = resources.getOrDefault(item, 0);
                if (count < 1 || available < count) {
                    return refuse("resource shortfall: " + item + " requested " + count + ", available " + available);
                }
                resources.put(item, available - count);
                inventory.merge(item, count, Integer::sum);
                gathered.merge(item, count, Integer::sum);
                return Node.Status.SUCCESS;
            });
        }

        @Override
        public Node craft(Recipe recipe, int times) {
            return new ActionNode("craft:" + recipe.output(), context -> {
                trace.add("craft " + times + " batch(es) " + recipe.output());
                if (recipe.needsStation() && !stations.contains(recipe.station())) {
                    return refuse("station unavailable: " + recipe.station());
                }
                Map<String, Integer> needed = new LinkedHashMap<>();
                recipe.ingredients().forEach(input -> needed.merge(input.item(), input.count() * times, Integer::sum));
                for (var input : needed.entrySet()) {
                    if (count(input.getKey()) < input.getValue()) {
                        return refuse("craft shortfall: " + input.getKey() + " needs " + input.getValue()
                                + ", has " + count(input.getKey()));
                    }
                }
                needed.forEach((item, count) -> inventory.put(item, count(item) - count));
                inventory.merge(recipe.output(), recipe.outputCount() * times, Integer::sum);
                return Node.Status.SUCCESS;
            });
        }

        @Override
        public Node ensureStation(String station) {
            return new ActionNode("station:" + station, context -> {
                trace.add("ensure station " + station);
                return stations.contains(station) ? Node.Status.SUCCESS : refuse("station unavailable: " + station);
            });
        }
    }
}
