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

    private static JsonObject dataOf(ToolRun run) {
        return dataOf(run.reply());
    }
}
