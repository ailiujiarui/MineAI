package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.MachineRecipeOps;
import com.dwinovo.numen.core.tools.RecipeProbe;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.util.List;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 通用能力契约 v0:适配器声明的机器配方({@code machine_recipe})。
 *
 * <p>参考适配器 {@code example-furnace.json} 声明了原版熔炉(方块/菜单 {@code minecraft:furnace}、
 * 配方类型 {@code minecraft:smelting}、槽位 input/fuel/output 0/1/2)。这里只读服务端配方本,不动世界。
 *
 * <p>用例要工具已经登记才跑得起来;登记由编排方接(见 {@code NumenCore})。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackMachineGameTests {

    @BeforeBatch(batch = "numen_pack_machine")
    public static void preparePackMachineBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 铁锭:参考适配器声明的熔炉能产它,输入是生铁,声明输入槽是 0。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_finds_the_declared_furnace(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_smelter", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", "minecraft:iron_ingot"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject data = JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
            JsonObject machine = firstMachineMaking(data, "minecraft:iron_ingot");
            helper.assertTrue(machine != null, "no machine entry for iron_ingot: " + run.reply());
            helper.assertTrue(machine.get("block").getAsString().equals("minecraft:furnace")
                            && machine.get("recipe_type").getAsString().equals("minecraft:smelting"),
                    "the declared furnace machine is not reported: " + run.reply());
            helper.assertTrue(machine.getAsJsonObject("slots").getAsJsonArray("input").get(0).getAsInt() == 0,
                    "the declared input slot is not 0: " + run.reply());
            helper.assertTrue(reportsInput(machine, "minecraft:raw_iron"),
                    "no reported recipe smelts minecraft:raw_iron: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 基岩:没有任何声明的机器产它(原版烧不出来,整合包里的机器配方也没有它) → 干净失败,措辞点名
     *  "no registered machine",并给回退建议。原来用泥土,但本整合包有一条空输入产泥土灌注配方,泥土已经不再合适。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_for_something_no_machine_makes_fails_cleanly(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_refuser", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", "minecraft:bedrock"));

        helper.succeedWhen(() -> {
            helper.assertTrue(!run.succeeded(), "a machine was claimed for bedrock: " + run.reply());
            helper.assertTrue(run.reply().contains("no registered machine makes minecraft:bedrock")
                            && run.reply().contains("lookup_recipe"),
                    "the failure does not point at the fallback: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * Mekanism 富集仓:从服务端配方本当场取一条 {@code mekanism:enriching} 产出的物品,再问 {@code machine_recipe},
     * 断言回执认的是富集仓、声明输入槽是探针实测的 2,且配方类型对得上。整合包里的槽号不靠猜。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_finds_the_declared_mekanism_enrichment_chamber(GameTestHelper helper) {
        assertDeclaredMachine(helper, "mekanism:enriching", "mekanism:enrichment_chamber", "input", 2);
    }

    /** AE2 压印器:四个受限输入槽 0-3、产物在 43,适配文件经示例铺开路径装载。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_finds_the_declared_ae2_inscriber(GameTestHelper helper) {
        assertDeclaredMachine(helper, "ae2:inscriber", "ae2:inscriber", "input", 0);
    }

    /** 注入的测试适配文件:一台声明了 EnderIO 合金炉的机器,一台"学来的"没有 recipeType 的机器。 */
    private static final String TEST_ADAPTERS = """
            {
              "id": "gametest-machines",
              "targetMod": "",
              "side": "server",
              "schema": 2,
              "machines": [
                {
                  "id": "gametest_enderio_alloy",
                  "block": "enderio:alloy_smelter",
                  "menu": "enderio:alloy_smelter",
                  "recipeType": "%s",
                  "slots": { "input": [0, 1, 2], "output": [3] },
                  "note": "injected by the machine_recipe gametest"
                },
                {
                  "id": "gametest_learned_blank",
                  "block": "gametest:learned_blank",
                  "menu": "gametest:learned_blank",
                  "recipeType": "",
                  "slots": { "input": [0], "output": [1] },
                  "note": "learned-style spec with no recipeType"
                }
              ]
            }
            """;

    /**
     * EnderIO 合金炉:它的 {@code getResultItem} 是空实现,服务端配方本读不出产出。{@code machine_recipe}
     * 经产出回退(反射 {@code output()})认出来,或者至少把它当 {@code output_unknown} 交回来——两者都不算失败。
     * 用例把声明了 {@code enderio:alloy_smelting} 的机器塞进适配表,再拿一条真实产物的物品 id 去问。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_surfaces_enderio_alloy_smelting_via_empty_result_fallback(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        RecipeType<?> alloy = recipeTypeOfClass(level, "AlloySmelting");
        if (alloy == null) {
            helper.succeed();   // 没装 EnderIO,跳过
            return;
        }
        ItemStack product = firstResolvedProduct(level, alloy);
        if (product.isEmpty()) {
            helper.fail("EnderIO alloy smelting has no output the fallback can read");
            return;
        }
        String item = BuiltInRegistries.ITEM.getKey(product.getItem()).toString();
        Path dir = installTestAdapters(BuiltInRegistries.RECIPE_TYPE.getKey(alloy).toString());
        NumenPlayer companion = spawnAt(helper, "gametest_enderio", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", item, "count", 1));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject machine = machineById(dataOf(run), "gametest_enderio_alloy");
            helper.assertTrue(machine != null, "the EnderIO alloy smelter is not reported: " + run.reply());
            helper.assertTrue(reportsOutputOrUnknown(machine, item),
                    "no EnderIO recipe for " + item + " surfaced: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
            restoreTestAdapters(dir);
        });
    }

    /**
     * 学来的机器没有 recipeType:搜遍全部配方类型也要找得到。注入一台空白 recipeType 的机器,拿一条熔炼产物
     * 去问,断言回执点名了这台机器,且每条命中都带上了真实配方类型 id——这台机器因此立即可用。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_with_a_blank_recipe_type_searches_every_registered_type(GameTestHelper helper)
            throws Exception {
        RecipeType<?> smelting = BuiltInRegistries.RECIPE_TYPE
                .getOptional(ResourceLocation.parse("minecraft:smelting")).orElse(null);
        if (smelting == null) {
            helper.succeed();
            return;
        }
        String item = firstProductOf(helper.getLevel(), smelting);
        if (item == null) {
            helper.fail("no smelting product found");
            return;
        }
        Path dir = installTestAdapters("minecraft:smelting");
        NumenPlayer companion = spawnAt(helper, "gametest_blank_type", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", item));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject machine = machineById(dataOf(run), "gametest_learned_blank");
            helper.assertTrue(machine != null, "the blank-recipeType machine found nothing: " + run.reply());
            helper.assertTrue(machine.get("recipe_type").getAsString().isEmpty(),
                    "the machine's own recipe_type should stay blank: " + run.reply());
            helper.assertTrue(hasTypedOutput(machine, item),
                    "the all-types search returned no recipe tagged with its type: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
            restoreTestAdapters(dir);
        });
    }

    /** 反向查:问一个已知输入(熔炼的生铁),回执该点名吃它的那台机器,并列出它作为输入。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_role_input_finds_the_consuming_machine(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_reverse", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe",
                args("item_id", "minecraft:raw_iron", "role", "input"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject data = dataOf(run);
            helper.assertTrue(data.get("role").getAsString().equals("input"),
                    "the reply does not mark the role: " + run.reply());
            JsonObject machine = machineForType(data, "minecraft:smelting");
            helper.assertTrue(machine != null,
                    "the furnace did not surface for a raw_iron input: " + run.reply());
            helper.assertTrue(reportsInput(machine, "minecraft:raw_iron"),
                    "the furnace recipe does not list raw_iron as an input: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 化学产出机器:氧化机吃物品、产气体,产出不是物品。反向查一个它的输入,机器要出现,配方的产出标
     * {@code output_unknown}——这正是"吃 X → 产气体 Y"在服务端能给出的样子。输入靠反射 {@code getInput()}
     * 读回来(Mekanism 的物品输入不走 Recipe 的 ingredient 表),读不回输入就跳过。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_machine")
    public static void machine_recipe_role_input_surfaces_a_chemical_machine_with_unknown_output(GameTestHelper helper) {
        RecipeType<?> oxidizing = BuiltInRegistries.RECIPE_TYPE
                .getOptional(ResourceLocation.parse("mekanism:oxidizing")).orElse(null);
        if (oxidizing == null) {
            helper.succeed();
            return;
        }
        List<ItemStack> inputs = firstResolvedInputs(helper.getLevel(), oxidizing);
        if (inputs.isEmpty()) {
            helper.fail("no item input found for mekanism:oxidizing");
            return;
        }
        String item = BuiltInRegistries.ITEM.getKey(inputs.get(0).getItem()).toString();
        NumenPlayer companion = spawnAt(helper, "gametest_chemical", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", item, "role", "input"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject machine = machineForType(dataOf(run), "mekanism:oxidizing");
            helper.assertTrue(machine != null,
                    "the chemical oxidizer did not surface for " + item + ": " + run.reply());
            helper.assertTrue(reportsUnknownOutput(machine),
                    "the chemical recipe's output should be marked unknown: " + run.reply());
            helper.assertTrue(reportsInput(machine, item),
                    "the oxidizer recipe does not list " + item + " as an input: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 把测试适配文件写进临时目录、连同用户适配目录一起装载;用完调 {@link #restoreTestAdapters(Path)} 还原。 */
    private static Path installTestAdapters(String enderioType) throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("numen-machine-adapters");
        java.nio.file.Files.writeString(dir.resolve("gametest-machines.json"),
                String.format(TEST_ADAPTERS, enderioType), java.nio.charset.StandardCharsets.UTF_8);
        AdapterManager.registry().reload(List.of(AdapterManager.dir(), dir), mod -> true, handler -> true);
        return dir;
    }

    /** 丢掉测试注入的适配文件,从磁盘重读回真实适配表(用户目录 + 插件自带)。 */
    private static void restoreTestAdapters(Path dir) {
        AdapterManager.reload();
        try {
            if (dir != null) {
                java.nio.file.Files.deleteIfExists(dir.resolve("gametest-machines.json"));
                java.nio.file.Files.deleteIfExists(dir);
            }
        } catch (java.io.IOException ignored) {
            // 临时目录清不掉无所谓,进程结束即回收
        }
    }

    /** 配方本里第一条类名含 {@code simpleNamePart} 的配方的类型;没有为 null。 */
    private static RecipeType<?> recipeTypeOfClass(ServerLevel level, String simpleNamePart) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (holder.value().getClass().getSimpleName().contains(simpleNamePart)) {
                return holder.value().getType();
            }
        }
        return null;
    }

    /** 某个配方类型下第一条读得出产出的配方的产出;没有为 EMPTY。 */
    private static ItemStack firstResolvedProduct(ServerLevel level, RecipeType<?> type) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != type) {
                continue;
            }
            List<ItemStack> outputs = MachineRecipeOps.outputsOf(recipe, level.registryAccess());
            if (!outputs.isEmpty()) {
                return outputs.get(0);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 某个配方类型下第一条读得出物品输入的配方的输入;没有为空表。 */
    private static List<ItemStack> firstResolvedInputs(ServerLevel level, RecipeType<?> type) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != type) {
                continue;
            }
            List<ItemStack> inputs = MachineRecipeOps.inputItems(recipe);
            if (!inputs.isEmpty()) {
                return inputs;
            }
        }
        return List.of();
    }

    /** 回执 data 里 id 等于 {@code id} 的那台机器;没有为 null。 */
    private static JsonObject machineById(JsonObject data, String id) {
        for (var element : data.getAsJsonArray("machines")) {
            JsonObject machine = element.getAsJsonObject();
            if (machine.get("id").getAsString().equals(id)) {
                return machine;
            }
        }
        return null;
    }

    /** 这台机器列出的任意一条配方,产出物品是它,或产出未知(标了 output_unknown)。 */
    private static boolean reportsOutputOrUnknown(JsonObject machine, String item) {
        for (var element : machine.getAsJsonArray("recipes")) {
            JsonObject recipe = element.getAsJsonObject();
            if (recipe.has("output_unknown") && recipe.get("output_unknown").getAsBoolean()) {
                return true;
            }
            if (recipe.has("output")
                    && recipe.getAsJsonObject("output").get("item").getAsString().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** 这台机器列出的任意一条配方,产出物品是它,且那条配方带上了非空的 recipe_type 标签。 */
    private static boolean hasTypedOutput(JsonObject machine, String item) {
        for (var element : machine.getAsJsonArray("recipes")) {
            JsonObject recipe = element.getAsJsonObject();
            String type = recipe.has("recipe_type") ? recipe.get("recipe_type").getAsString() : "";
            if (!type.isBlank() && recipe.has("output")
                    && recipe.getAsJsonObject("output").get("item").getAsString().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** 这台机器列出的任意一条配方标了 output_unknown。 */
    private static boolean reportsUnknownOutput(JsonObject machine) {
        for (var element : machine.getAsJsonArray("recipes")) {
            JsonObject recipe = element.getAsJsonObject();
            if (recipe.has("output_unknown") && recipe.get("output_unknown").getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通用断言:从配方本找一条目标配方类型产出的物品,问 {@code machine_recipe},核对回执里那台机器的方块、
     * 配方类型、以及某个角色的第一个槽号。配方类型不在场(模组没装)直接成功返回,当作跳过。
     */
    private static void assertDeclaredMachine(GameTestHelper helper, String recipeType, String block,
                                              String role, int firstSlot) {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.getOptional(ResourceLocation.parse(recipeType)).orElse(null);
        if (type == null) {
            helper.succeed();
            return;
        }
        String item = firstProductOf(helper.getLevel(), type);
        if (item == null) {
            helper.fail("no item product found for " + recipeType);
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_machine", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_recipe", args("item_id", item));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.succeeded(), "machine_recipe refused: " + run.reply());
            JsonObject machine = machineForType(dataOf(run), recipeType);
            helper.assertTrue(machine != null, "no declared " + recipeType + " machine: " + run.reply());
            helper.assertTrue(machine.get("block").getAsString().equals(block),
                    "the declared block is not " + block + ": " + run.reply());
            helper.assertTrue(machine.getAsJsonObject("slots").getAsJsonArray(role).get(0).getAsInt() == firstSlot,
                    "the declared " + role + " slot is not " + firstSlot + ": " + run.reply());
            helper.assertTrue(reportsOutput(machine, item),
                    "the " + recipeType + " machine does not produce " + item + ": " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 配方本里第一条属于这个配方类型、能安全读输入且展示产出非空的物品 id;没有为 null。
     *  与 {@code machine_recipe} 用同一道安全门,免得挑到它一定会跳过的坏配方。 */
    private static String firstProductOf(ServerLevel level, RecipeType<?> type) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != type || !RecipeProbe.usableIngredients(recipe)) {
                continue;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(level.registryAccess());
            } catch (RuntimeException broken) {
                continue;
            }
            if (result != null && !result.isEmpty()) {
                return BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            }
        }
        return null;
    }

    /** 回执 data 里配方类型等于 {@code recipeType} 的那台机器;没有为 null。 */
    private static JsonObject machineForType(JsonObject data, String recipeType) {
        for (var element : data.getAsJsonArray("machines")) {
            JsonObject machine = element.getAsJsonObject();
            if (machine.get("recipe_type").getAsString().equals(recipeType)) {
                return machine;
            }
        }
        return null;
    }

    private static JsonObject dataOf(ToolRun run) {
        return JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
    }

    /** 这台机器列出的任意一条配方,产出物品 id 是不是它。 */
    private static boolean reportsOutput(JsonObject machine, String item) {
        for (var recipe : machine.getAsJsonArray("recipes")) {
            if (recipe.getAsJsonObject().getAsJsonObject("output").get("item").getAsString().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** 这台机器列出的任意一条配方里,有没有一个输入 ingredient 认这个物品 id。铁锭的熔炼配方有好几条
     *  (生铁、铁矿石、深层铁矿石),配方本里的先后不定,所以逐条找而不是只看第一条。 */
    private static boolean reportsInput(JsonObject machine, String item) {
        for (var recipe : machine.getAsJsonArray("recipes")) {
            for (var input : recipe.getAsJsonObject().getAsJsonArray("inputs")) {
                for (var candidate : input.getAsJsonObject().getAsJsonArray("items")) {
                    if (candidate.getAsString().equals(item)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 产出目标物品的那台机器;没有为 null。 */
    private static JsonObject firstMachineMaking(JsonObject data, String item) {
        JsonArray machines = data.getAsJsonArray("machines");
        for (int i = 0; i < machines.size(); i++) {
            JsonObject machine = machines.get(i).getAsJsonObject();
            for (var recipe : machine.getAsJsonArray("recipes")) {
                if (recipe.getAsJsonObject().getAsJsonObject("output").get("item").getAsString().equals(item)) {
                    return machine;
                }
            }
        }
        return null;
    }
}
