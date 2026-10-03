package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * AE2 的一体化能力:{@code ae2_network} 报网格状态,{@code machine_config} 认得线缆上的 part。
 *
 * <p>AE2 不在场时,{@code ae2_network} 根本不会被登记(它随联动插件一起装),相关用例就地成功跳过——
 * 没装 AE2 的环境不会因此变红。用例只用方块注册表摆题面,不编译依赖 AE2 的类。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackAe2GameTests {

    @BeforeBatch(batch = "numen_pack_ae2")
    public static void prepareAe2Batch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 放一台 AE2 能量接收器(自成一格的机器),{@code ae2_network} 报得出这张网格。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_ae2")
    public static void ae2_network_reports_a_placed_device(GameTestHelper helper) {
        Block acceptor = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("ae2:energy_acceptor"))
                .orElse(null);
        if (acceptor == null || ToolRegistry.get("ae2_network") == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(at, acceptor.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_ae2_net", new BlockPos(2, 2, 2), false);
        AtomicReference<String> last = new AtomicReference<>();

        helper.startSequence()
                // 节点要等网格 boot 完才挂上;轮询到它进网为止。
                .thenWaitUntil(() -> {
                    ToolRun run = call(companion, "ae2_network",
                            args("x", at.getX(), "y", at.getY(), "z", at.getZ()));
                    helper.assertTrue(run.done(), "ae2_network has not replied");
                    helper.assertTrue(run.succeeded(), "ae2_network refused the grid: " + run.reply());
                    JsonObject data = dataOf(run);
                    helper.assertTrue(data.get("on_grid").getAsBoolean(),
                            "the device was not reported on a grid yet: " + run.reply());
                    last.set(run.reply());
                })
                .thenExecute(() -> {
                    JsonObject data = dataOf(last.get());
                    helper.assertTrue(data.has("controller_state"), "no controller state: " + last.get());
                    helper.assertTrue(data.has("channels_used"), "no channel usage: " + last.get());
                    helper.assertTrue(data.get("device_count").getAsInt() >= 1,
                            "the grid has no devices: " + last.get());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 普通方块不在任何 AE2 网格上:{@code ae2_network} 干净回一份"不在网格上",不抛。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_ae2")
    public static void ae2_network_on_a_plain_block_reports_no_grid(GameTestHelper helper) {
        if (ToolRegistry.get("ae2_network") == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_ae2_nogrid", new BlockPos(2, 2, 2), false);
        ToolRun run = call(companion, "ae2_network",
                args("x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "ae2_network has not replied");
            helper.assertTrue(run.succeeded(), "ae2_network error on a plain block: " + run.reply());
            helper.assertTrue(!dataOf(run).get("on_grid").getAsBoolean(),
                    "a plain stone was reported on a grid: " + run.reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** AE2 线缆方块上没有可配 part:{@code machine_config} 认出这是 part host 并干净说没有 part。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_ae2")
    public static void machine_config_recognises_an_ae2_cable_as_a_part_host(GameTestHelper helper) {
        Block cable = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("ae2:cable_bus")).orElse(null);
        if (cable == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(at, cable.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_ae2_cable", new BlockPos(2, 2, 2), false);
        ToolRun run = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(!run.succeeded(), "a bare cable exposed config: " + run.reply());
            helper.assertTrue(run.reply().contains("part host"),
                    "the failure does not name the AE2 part host: " + run.reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    private static JsonObject dataOf(String reply) {
        return JsonParser.parseString(reply).getAsJsonObject().getAsJsonObject("data");
    }

    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_pack_ae2")
    public static void ae2_request_craft_completes_and_returns_output_to_me(GameTestHelper helper) {
        craftingFixture(helper, true, true);
    }

    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_pack_ae2")
    public static void ae2_request_craft_reports_missing_ingredients_without_submission(GameTestHelper helper) {
        craftingFixture(helper, false, true);
    }

    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_pack_ae2")
    public static void ae2_request_craft_reports_no_cpu_without_consuming_material(GameTestHelper helper) {
        craftingFixture(helper, true, false);
    }

    /** 使用真实 AE2 CPU、驱动器、合成模式与装配室;反射避免无 AE2 环境加载它的类型。 */
    private static void craftingFixture(GameTestHelper helper, boolean material, boolean cpu) {
        if (ToolRegistry.get("request_craft") == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos provider = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos drive = provider.north();
        BlockPos energy = drive.north();
        placeAe2(level, provider, "pattern_provider");
        placeAe2(level, provider.east(), "molecular_assembler");
        placeAe2(level, drive, "drive");
        placeAe2(level, energy, "energy_cell");
        if (cpu) placeAe2(level, provider.south(), "1k_crafting_storage");
        NumenPlayer body = spawnAt(helper, "gt_ae2_craft", new BlockPos(3, 2, 5), false);
        Permission.setMode(body, Mode.BYPASS);
        AtomicReference<Object> grid = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (grid.get() != null) invoke(invoke(grid.get(), "getEnergyService"), "injectPower", 10000.0,
                    aeEnum("appeng.api.config.Actionable", "MODULATE"));
        });
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    Object host = invoke(aeClass("appeng.api.networking.GridHelper"), "getNodeHost", level, drive);
                    Object node = invoke(host, "getGridNode", (Object) null);
                    for (var side : net.minecraft.core.Direction.values()) {
                        if (node == null) node = invoke(host, "getGridNode", side);
                    }
                    helper.assertTrue(node != null, "drive has no grid node");
                    grid.set(invoke(node, "getGrid"));
                    Object inv = invoke(level.getBlockEntity(drive), "getInternalInventory");
                    var cell = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("ae2:item_storage_cell_1k"))
                            .orElseThrow();
                    invoke(inv, "setItemDirect", 0, new ItemStack(cell));
                    var recipe = level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                            .filter(r -> r.id().equals(ResourceLocation.parse("minecraft:oak_planks")))
                            .findFirst().orElseThrow();
                    ItemStack[] inputs = new ItemStack[9];
                    java.util.Arrays.fill(inputs, ItemStack.EMPTY);
                    inputs[0] = new ItemStack(Items.OAK_LOG);
                    Object pattern = invoke(aeClass("appeng.api.crafting.PatternDetailsHelper"), "encodeCraftingPattern",
                            recipe, inputs, new ItemStack(Items.OAK_PLANKS, 4), false, false);
                    Object logic = invoke(level.getBlockEntity(provider), "getLogic");
                    invoke(invoke(logic, "getPatternInv"), "setItemDirect", 0, pattern);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    if (material) {
                        Object storage = invoke(invoke(grid.get(), "getStorageService"), "getInventory");
                        Object inserted = invoke(storage, "insert", aeKey(Items.OAK_LOG), 1L,
                                aeEnum("appeng.api.config.Actionable", "MODULATE"),
                                invoke(aeClass("appeng.api.networking.security.IActionSource"), "ofPlayer", body));
                        helper.assertTrue(((Number) inserted).longValue() == 1, "failed to seed ME storage");
                    }
                    ToolRun run = call(body, "request_craft", args("x", drive.getX(), "y", drive.getY(),
                            "z", drive.getZ(), "item", "minecraft:oak_planks", "count", 4));
                    helper.assertTrue(run.done() && run.succeeded(), "request rejected: " + run.reply());
                    JsonObject data = dataOf(run);
                    helper.assertTrue(data.get("state").getAsString().equals("calculating"),
                            "must report calculation, not premature completion: " + run.reply());
                    request.set(data.get("request_id").getAsString());
                })
                .thenWaitUntil(() -> {
                    ToolRun run = call(body, "request_craft", args("request_id", request.get()));
                    String state = dataOf(run).get("state").getAsString();
                    helper.assertTrue(state.equals(material && cpu ? "completed" : "failed"),
                            "unexpected state: " + run.reply() + "; assembler progress="
                                    + invoke(level.getBlockEntity(provider.east()), "getCraftingProgress")
                                    + " pattern=" + invoke(level.getBlockEntity(provider.east()), "getCurrentPattern")
                                    + " active=" + invoke(level.getBlockEntity(provider.east()), "isActive")
                                    + " cpus=" + cpuDetails(grid.get())
                                    + " lock=" + invoke(invoke(level.getBlockEntity(provider), "getLogic"), "getCraftingLockedReason"));
                    if (!material) helper.assertTrue(run.reply().contains("Missing ingredients"), run.reply());
                    if (material && !cpu) helper.assertTrue(run.reply().contains("NO_CPU_FOUND"), run.reply());
                    Object stock = invoke(invoke(grid.get(), "getStorageService"), "getInventory");
                    Object available = invoke(stock, "getAvailableStacks");
                    long planks = ((Number) invoke(available, "get", aeKey(Items.OAK_PLANKS))).longValue();
                    long logs = ((Number) invoke(available, "get", aeKey(Items.OAK_LOG))).longValue();
                    helper.assertTrue(planks == (material && cpu ? 4 : 0), "wrong ME plank count: " + planks);
                    helper.assertTrue(logs == (material && !cpu ? 1 : 0), "material was lost or duplicated: " + logs);
                    CompanionFactory.despawn(level.getServer(), body);
                })
                .thenSucceed();
    }

    private static void placeAe2(ServerLevel level, BlockPos pos, String id) {
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("ae2:" + id))
                .orElseThrow().defaultBlockState());
    }

    private static String cpuDetails(Object grid) {
        StringBuilder result = new StringBuilder();
        for (Object cpu : (Iterable<?>) invoke(invoke(grid, "getCraftingService"), "getCpus")) {
            try {
                Object logic = cpu.getClass().getField("craftingLogic").get(cpu);
                result.append(invoke(cpu, "getJobStatus")).append(" suspended=")
                        .append(invoke(logic, "isJobSuspended")).append(" logs=")
                        .append(invoke(logic, "getStored", aeKey(Items.OAK_LOG))).append(" waiting=")
                        .append(invoke(logic, "getWaitingFor", aeKey(Items.OAK_PLANKS)));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        return result.toString();
    }

    private static Class<?> aeClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }

    private static Object aeEnum(String name, String value) {
        try { return aeClass(name).getField(value).get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Object aeKey(net.minecraft.world.item.Item item) {
        return invoke(aeClass("appeng.api.stacks.AEItemKey"), "of", item);
    }

    private static Object invoke(Object target, String name, Object... args) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (var method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> p = parameters[i];
                if (p == int.class) p = Integer.class;
                if (p == long.class) p = Long.class;
                if (p == double.class) p = Double.class;
                if (p == boolean.class) p = Boolean.class;
                if (args[i] != null && !p.isInstance(args[i])) matches = false;
            }
            if (!matches) continue;
            try { return method.invoke(target instanceof Class<?> ? null : target, args); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(name + ": " + e, e); }
        }
        throw new IllegalStateException("No matching AE2 method: " + type + "." + name);
    }

    private static JsonObject dataOf(ToolRun run) {
        return dataOf(run.reply());
    }
}
