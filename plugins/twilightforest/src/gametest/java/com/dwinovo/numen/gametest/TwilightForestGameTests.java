package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 暮色森林在场时她的通用本事:走暮色自己的地面({@code numen.move.to} 在自然地形上走一段)、挖暮色自己的
 * 一小丛木头({@code numen.work.dig} 一次挖掉立着的三格)。场景都在暮色维度里(twilightforest:twilight_forest),
 * 同伴用真实的生成路径拉起来——跨维度、模组方块、模组地形一次都走一遍。
 *
 * <p>只在挂着暮色森林的那一次跑批里跑({@code :plugins:twilightforest:runGameTestServer},见插件的 build.gradle),
 * 套件 {@code numen_twilightforest}(见 {@link TwilightForestGameTestSuite});Numen 那一次跑批里没有这些用例,
 * 也没有暮色森林。
 */
public class TwilightForestGameTests {

    private static final String BATCH = "numen_twilightforest";

    /** 暮色的维度键:维度注册表里的 twilightforest:twilight_forest。 */
    private static final ResourceKey<Level> TWILIGHT = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("twilightforest", "twilight_forest"));

    /** 测试立的那几格木头:暮色橡木原木,经注册表按名字取,编译期不引用暮色的类。 */
    private static final ResourceLocation LOG = ResourceLocation.fromNamespaceAndPath("twilightforest", "twilight_oak_log");

    @BeforeBatch(batch = BATCH)
    public static void prepareTwilightBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 暮色维度;这一次跑批里没挂上暮色森林的话,这里就说不出来由。 */
    private static ServerLevel twilight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(TWILIGHT);
        if (level == null) {
            throw new GameTestAssertException("暮色的维度不在,这次跑批里暮色森林没有加载");
        }
        return level;
    }

    /** 这一列的地面(不算树叶):出生的那一格站在它上面。 */
    private static BlockPos surface(ServerLevel level, int x, int z) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
    }

    /** 暮色橡木原木的方块;暮色森林在场时注册表里就有。 */
    private static Block logBlock(GameTestHelper helper) {
        Block log = BuiltInRegistries.BLOCK.get(LOG);
        helper.assertTrue(log != Blocks.AIR, "方块注册表里没有 " + LOG);
        return log;
    }

    /** 走暮色的地面:出生在 (8,8) 的地面上,{@code numen.move.to} 到十六格外 (8,24) 的地面,停在跟前。 */
    @GameTest(template = "floor16", timeoutTicks = 2400, batch = BATCH)
    public static void she_walks_the_twilight_ground(GameTestHelper helper) {
        ServerLevel twilight = twilight(helper);
        BlockPos from = surface(twilight, 8, 8);
        BlockPos to = surface(twilight, 8, 24);
        NumenPlayer her = CompanionFactory.spawn(helper.getLevel().getServer(), UUID.randomUUID(),
                "gametest_twilight_walker", UUID.randomUUID(), twilight, Vec3.atBottomCenterOf(from));
        AtomicReference<ToolRun> walk = new AtomicReference<>();

        succeedWhen(helper, () -> {
            if (walk.get() == null) {
                if (!twilight.isLoaded(from) || !twilight.isLoaded(to)) {
                    throw new GameTestAssertException("暮色的那片区块还没加载");
                }
                walk.set(lua(her, "numen.move.to(" + xyz(to) + ", 2)"));
                return;
            }
            ToolRun run = walk.get();
            helper.assertTrue(run.done(), "move.to has not finished");
            double apart = Math.hypot(her.getX() - to.getX() - 0.5, her.getZ() - to.getZ() - 0.5);
            helper.assertTrue(run.succeeded() && her.serverLevel() == twilight && apart <= 3
                            && Math.abs(her.getY() - to.getY()) <= 4,
                    "she did not walk the twilight ground: " + run.outcome() + ", still " + Math.round(apart)
                            + " blocks from the spot");
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 挖暮色的木头:场子里立三根暮色橡木原木,{@code numen.work.dig} 一次挖掉,三格都成空气。 */
    @GameTest(template = "floor16", timeoutTicks = 2400, batch = BATCH)
    public static void she_digs_twilight_oak_logs_on_the_twilight_ground(GameTestHelper helper) {
        ServerLevel twilight = twilight(helper);
        Block log = logBlock(helper);
        BlockPos ground = surface(twilight, 8, 8);
        BlockPos[] logs = {
                ground.offset(2, 0, 0),
                ground.offset(2, 1, 0),
                ground.offset(2, 0, 1),
        };
        NumenPlayer her = CompanionFactory.spawn(helper.getLevel().getServer(), UUID.randomUUID(),
                "gametest_twilight_digger", UUID.randomUUID(), twilight, Vec3.atBottomCenterOf(ground));
        her.getInventory().add(new ItemStack(Items.IRON_AXE));
        AtomicReference<ToolRun> dig = new AtomicReference<>();

        succeedWhen(helper, () -> {
            if (dig.get() == null) {
                if (!twilight.isLoaded(ground.offset(-4, 0, -4)) || !twilight.isLoaded(ground.offset(6, 0, 6))) {
                    throw new GameTestAssertException("暮色的那片区块还没加载");
                }
                // 立木头前先把这一小片清空:场子是暮色自己的地面,只把要挖的三格周围清出来,底下不动
                for (BlockPos cell : BlockPos.betweenClosed(ground.offset(-2, 0, -2), ground.offset(4, 4, 4))) {
                    twilight.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
                }
                for (BlockPos cell : logs) {
                    twilight.setBlockAndUpdate(cell, log.defaultBlockState());
                }
                StringBuilder cells = new StringBuilder("{");
                for (BlockPos cell : logs) {
                    cells.append("{name = \"").append(LOG).append("\", pos = ").append(xyz(cell)).append("}, ");
                }
                cells.setLength(cells.length() - 2);
                cells.append("}");
                dig.set(lua(her, "numen.work.dig(" + cells + ", {count = 3})"));
                return;
            }
            ToolRun run = dig.get();
            helper.assertTrue(run.done(), "dig has not finished");
            helper.assertTrue(run.succeeded(), "the logs were not dug: " + run.outcome());
            for (BlockPos cell : logs) {
                helper.assertTrue(twilight.getBlockState(cell).isAir(), "the log is still standing at " + words(cell));
            }
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }
}
