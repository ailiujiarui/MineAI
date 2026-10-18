package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 机械动力在场时她的通用本事:放机械动力自己的方块({@code numen.build.place} 拿它的物品放三格)、
 * 挖机械动力自己的方块({@code numen.work.dig} 一次挖掉三格)。机械动力没有自己的维度,场景就用
 * Numen 的场地(floor16);走的是真实路径:模组方块经注册表按名字取、模组物品进她的背包,一次都走一遍。
 *
 * <p>只在挂着机械动力的那一次跑批里跑({@code :plugins:create:runGameTestServer},见插件的 build.gradle),
 * 套件 {@code numen_create}(见 {@link CreateGameTestSuite});Numen 那一次跑批里没有这些用例,也没有机械动力。
 */
public class CreateGameTests {

    private static final String BATCH = "numen_create";

    /** 测试用的机械动力方块:安山机壳,经注册表按名字取,编译期不引用机械动力的类。 */
    private static final ResourceLocation CASING = ResourceLocation.fromNamespaceAndPath("create", "andesite_casing");

    @BeforeBatch(batch = BATCH)
    public static void prepareCreateBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 安山机壳的方块;机械动力在场时注册表里就有。 */
    private static Block casingBlock(GameTestHelper helper) {
        Block casing = BuiltInRegistries.BLOCK.get(CASING);
        helper.assertTrue(casing != Blocks.AIR, "方块注册表里没有 " + CASING);
        return casing;
    }

    /** 要动的那三格:地板上并排一格宽的一行(rel y = 2 是地板上面的第一层)。 */
    private static List<BlockPos> cells(GameTestHelper helper) {
        return List.of(
                helper.absolutePos(new BlockPos(8, 2, 8)),
                helper.absolutePos(new BlockPos(9, 2, 8)),
                helper.absolutePos(new BlockPos(10, 2, 8)));
    }

    /** 这三格写成 {@code numen.build.place} / {@code numen.work.dig} 的 Lua 一串 Block。 */
    private static String cellsLua(List<BlockPos> cells) {
        StringBuilder spec = new StringBuilder("{");
        for (BlockPos cell : cells) {
            spec.append("{name = \"").append(CASING).append("\", pos = ").append(xyz(cell)).append("}, ");
        }
        spec.setLength(spec.length() - 2);
        spec.append("}");
        return spec.toString();
    }

    /** 放机械动力的方块:备三块安山机壳给她,{@code numen.build.place} 一次放齐,三格都成机壳。 */
    @GameTest(template = "floor16", timeoutTicks = 2400, batch = BATCH)
    public static void she_places_create_blocks(GameTestHelper helper) {
        List<BlockPos> cells = cells(helper);
        Block casing = casingBlock(helper);
        NumenPlayer her = spawnAt(helper, "gametest_create_builder", new BlockPos(6, 2, 5), false);
        her.getInventory().add(new ItemStack(casing.asItem(), 3));
        AtomicReference<ToolRun> place = new AtomicReference<>();

        succeedWhen(helper, () -> {
            if (place.get() == null) {
                place.set(lua(her, "numen.build.place(" + cellsLua(cells) + ")"));
                return;
            }
            ToolRun run = place.get();
            helper.assertTrue(run.done(), "build.place has not finished");
            helper.assertTrue(run.succeeded(), "the casings were not placed: " + run.outcome());
            for (BlockPos cell : cells) {
                helper.assertTrue(casing.equals(helper.getLevel().getBlockState(cell).getBlock()),
                        "no casing at " + words(cell));
            }
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 挖机械动力的方块:场子里立三格安山机壳,{@code numen.work.dig} 一次挖掉,三格都成空气。 */
    @GameTest(template = "floor16", timeoutTicks = 2400, batch = BATCH)
    public static void she_digs_create_blocks(GameTestHelper helper) {
        Block casing = casingBlock(helper);
        List<BlockPos> cells = cells(helper);
        for (BlockPos cell : cells) {
            helper.getLevel().setBlockAndUpdate(cell, casing.defaultBlockState());
        }
        NumenPlayer her = spawnAt(helper, "gametest_create_digger", new BlockPos(6, 2, 5), false);
        her.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        AtomicReference<ToolRun> dig = new AtomicReference<>();

        succeedWhen(helper, () -> {
            if (dig.get() == null) {
                dig.set(lua(her, "numen.work.dig(" + cellsLua(cells) + ", {count = 3})"));
                return;
            }
            ToolRun run = dig.get();
            helper.assertTrue(run.done(), "dig has not finished");
            helper.assertTrue(run.succeeded(), "the casings were not dug: " + run.outcome());
            for (BlockPos cell : cells) {
                helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(),
                        "the casing is still standing at " + words(cell));
            }
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }
}
