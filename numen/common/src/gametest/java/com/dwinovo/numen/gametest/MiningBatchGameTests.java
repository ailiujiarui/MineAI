package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 采集批次:限制本次产量,受伤或看见危险时把进度交回脚本调用方。 */
public class MiningBatchGameTests {

    @BeforeBatch(batch = "numen_mining_batches")
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    @BeforeBatch(batch = "numen_mining_visibility")
    public static void prepareVisibility(ServerLevel level) {
        settleWorld(level, Difficulty.NORMAL, MIDNIGHT);
    }

    private static NumenPlayer miner(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_mining_batch", new BlockPos(3, 2, 3), false);
        body.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        body.getFoodData().setFoodLevel(17);
        return body;
    }

    private static List<BlockPos> ores(GameTestHelper helper, int count) {
        List<BlockPos> ores = IntStream.range(0, count)
                .mapToObj(i -> helper.absolutePos(new BlockPos(6 + i, 2, 3))).toList();
        ores.forEach(p -> helper.getLevel().setBlockAndUpdate(p, Blocks.IRON_ORE.defaultBlockState()));
        return ores;
    }

    private static void cleanup(GameTestHelper helper, NumenPlayer body, Entity... fixtures) {
        for (Entity entity : fixtures) entity.discard();
        CompanionFactory.despawn(helper.getLevel().getServer(), body);
    }

    private static String mine(int count) {
        return "return numen.work.mine(numen.scan.blocks(\"iron_ore\", {radius = 10})[1], {count = " + count + "})";
    }

    private static JsonObject stopped(GameTestHelper helper, ToolRun run, int dug) {
        helper.assertTrue(!run.ranToTheEnd(), "unsafe batch ran to completion: " + run.data());
        JsonObject error = run.data().getAsJsonObject("error");
        helper.assertTrue(error != null && error.get("kind").getAsString().equals("failed"),
                "batch did not return a structured failure: " + run.data());
        JsonObject progress = error.getAsJsonObject("data");
        helper.assertTrue(progress != null && progress.get("reason").getAsString().equals("unsafe")
                        && progress.get("dug").getAsInt() == dug,
                "batch lost its reason or partial progress: " + run.data());
        return progress;
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = "numen_mining_batches")
    public static void mine_collects_only_the_requested_batch(GameTestHelper helper) {
        List<BlockPos> ores = ores(helper, 4);
        NumenPlayer body = miner(helper);
        ToolRun[] run = new ToolRun[1];
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(body.onGround(), "miner has not landed"))
                .thenExecute(() -> run[0] = lua(body, mine(2)))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "batch has not returned"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].ranToTheEnd() && run[0].data().get("returned").getAsInt() == 2
                                    && body.getInventory().countItem(Items.RAW_IRON) == 2,
                            "two-cell batch was not collected: " + run[0].data());
                    helper.assertTrue(ores.stream().filter(p -> helper.getLevel().getBlockState(p).is(Blocks.IRON_ORE))
                                    .count() == 2,
                            "batch kept mining instead of leaving the rest for the next decision");
                    cleanup(helper, body);
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = "numen_mining_batches")
    public static void a_hurt_miner_returns_before_starting_a_walk(GameTestHelper helper) {
        List<BlockPos> ores = ores(helper, 1);
        NumenPlayer body = miner(helper);
        body.setHealth(9.0f);
        ToolRun[] run = new ToolRun[1];
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(body.onGround(), "miner has not landed"))
                .thenExecute(() -> run[0] = lua(body, mine(1)))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "hurt batch has not returned"))
                .thenExecute(() -> {
                    stopped(helper, run[0], 0);
                    helper.assertTrue(run[0].tasks("numen.move.go").isEmpty()
                                    && run[0].tasks("numen.work.dig").isEmpty()
                                    && helper.getLevel().getBlockState(ores.getFirst()).is(Blocks.IRON_ORE),
                            "hurt miner started a resource task");
                    cleanup(helper, body);
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 1000, batch = "numen_mining_batches")
    public static void a_batch_returns_partial_progress_before_collecting_in_danger(GameTestHelper helper) {
        BlockPos ore = ores(helper, 1).getFirst();
        NumenPlayer body = miner(helper);
        ToolRun[] run = new ToolRun[1];
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(body.onGround(), "miner has not landed"))
                .thenExecute(() -> run[0] = lua(body, mine(1)))
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getBlockState(ore).isAir(),
                        "the ore has not been dug"))
                .thenExecute(() -> body.setHealth(9.0f))
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "batch did not return after being hurt"))
                .thenExecute(() -> {
                    JsonObject progress = stopped(helper, run[0], 1);
                    helper.assertTrue(progress.has("drops") && !progress.getAsJsonArray("drops").isEmpty(),
                            "the dropped ore was omitted from the partial account");
                    helper.assertTrue(body.getInventory().countItem(Items.RAW_IRON) == 0
                                    && run[0].tasks("numen.move.go").isEmpty(),
                            "hurt miner walked back to collect instead of returning its progress");
                    cleanup(helper, body);
                }).thenSucceed();
    }

    @GameTest(template = "floor20", timeoutTicks = 1500, batch = "numen_mining_visibility")
    public static void mining_stops_for_visible_hostiles_but_not_ones_behind_a_wall(GameTestHelper helper) {
        ores(helper, 1);
        NumenPlayer body = miner(helper);
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(13, 2, 3));
        zombie.setNoAi(true);
        for (int z = 1; z <= 6; z++) {
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(9, y, z), Blocks.STONE);
        }
        ToolRun[] run = new ToolRun[1];
        BlockPos next = helper.absolutePos(new BlockPos(7, 2, 3));
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(body.onGround(), "miner has not landed"))
                .thenExecute(() -> {
                    helper.assertTrue(!body.hasLineOfSight(zombie), "the wall did not block the hostile");
                    run[0] = lua(body, mine(1));
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "occluded-hostile batch has not returned"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].ranToTheEnd() && body.getInventory().countItem(Items.RAW_IRON) == 1,
                            "a hostile behind the wall blocked safe mining: " + run[0].data());
                    helper.getLevel().setBlockAndUpdate(next, Blocks.IRON_ORE.defaultBlockState());
                    for (int z = 1; z <= 6; z++) {
                        for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(9, y, z), Blocks.AIR);
                    }
                    helper.assertTrue(body.hasLineOfSight(zombie), "the hostile did not become visible");
                    run[0] = lua(body, mine(1));
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "visible-hostile batch has not returned"))
                .thenExecute(() -> {
                    stopped(helper, run[0], 0);
                    helper.assertTrue(run[0].tasks("numen.work.dig").isEmpty()
                                    && helper.getLevel().getBlockState(next).is(Blocks.IRON_ORE)
                                    && body.getInventory().countItem(Items.RAW_IRON) == 1,
                            "miner continued through a visible hostile instead of returning");
                    cleanup(helper, body, zombie);
                }).thenSucceed();
    }
}
