package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * {@code routine}:可执行技能库——把一段跑通过的命令序列存下来,之后检索、按序回放。这里钉三件事:
 * 存进去之后 {@code list} 看得到;回放的每一步真的动了世界、且按序(放方块、再扳拉杆);回放一个不存在的
 * 名字时是一句干净的点名失败,而不是硬崩。
 *
 * <p>步骤用纯原版的 {@code use block}——不碰任何模组内容,干净测试服里也成立。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackRoutineGameTests {

    @BeforeBatch(batch = "numen_pack_routine")
    public static void prepareRoutineBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 存一条两步 routine:先在一块地板上放一块圆石,再扳下旁边一根拉杆。回放它,断言两件事都发生、
     * 手里的圆石少了一块,并且 {@code list} 里出现了这条 routine(带说明)。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_pack_routine")
    public static void routine_saves_lists_and_replays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(7, 1, 6));
        BlockPos lever = helper.absolutePos(new BlockPos(5, 2, 9));
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR));

        NumenPlayer companion = spawnAt(helper, "gametest_routine", new BlockPos(5, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));

        String step1 = "use block right " + xyz(floor) + " --item minecraft:cobblestone";
        String step2 = "use block right " + xyz(lever);
        ToolRun save = call(companion, "routine", args(
                "action", "save",
                "name", "routine_pave_and_flip",
                "description", "place one cobblestone then flip a lever",
                "steps", List.of(step1, step2),
                "args", List.of()));
        ToolRun list = call(companion, "routine", args("action", "list"));
        ToolRun run = call(companion, "routine", args("action", "run", "name", "routine_pave_and_flip"));

        succeedWhen(helper, () -> {
            helper.assertTrue(save.succeeded(), "save was refused: " + save.reply());
            helper.assertTrue(list.succeeded() && list.reply().contains("routine_pave_and_flip")
                            && list.reply().contains("place one cobblestone then flip a lever"),
                    "the saved routine is not in `list`: " + list.reply());
            helper.assertTrue(run.done(), "the routine has not finished");
            helper.assertTrue(run.succeeded(), "the routine did not run through: " + run.reply());
            helper.assertTrue(level.getBlockState(floor.above()).is(Blocks.COBBLESTONE),
                    "step 1 did not place the cobblestone: " + run.reply());
            helper.assertTrue(level.getBlockState(lever).getValue(LeverBlock.POWERED),
                    "step 2 did not flip the lever: " + run.reply());
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "placing the block did not spend one cobblestone: " + run.reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 回放一条以后台活开头的 routine:第一步 {@code move goto} 受理即回执(task_id),不是做完。routine 必须
     * 在这里停住,而不是抢跑第二步放方块;回执要说清停在第几步、剩下的步骤还没跑。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_pack_routine")
    public static void routine_pauses_at_a_background_step(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(7, 1, 6));
        BlockPos target = helper.absolutePos(new BlockPos(9, 2, 6));

        NumenPlayer companion = spawnAt(helper, "gametest_routine_bg", new BlockPos(5, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 2));

        String step1 = "move goto --x " + target.getX() + " --z " + target.getZ();
        String step2 = "use block right " + xyz(floor) + " --item minecraft:cobblestone";
        ToolRun save = call(companion, "routine", args(
                "action", "save",
                "name", "routine_bg_move_then_place",
                "description", "move (background) then place",
                "steps", List.of(step1, step2),
                "args", List.of()));
        ToolRun run = call(companion, "routine", args("action", "run", "name", "routine_bg_move_then_place"));

        succeedWhen(helper, () -> {
            helper.assertTrue(save.succeeded(), "save was refused: " + save.reply());
            helper.assertTrue(run.done(), "the routine never answered");
            helper.assertTrue(run.succeeded(), "the pause is a successful partial result: " + run.reply());
            helper.assertTrue(run.reply().contains("paused at step 1"),
                    "the routine did not pause at the background step: " + run.reply());
            helper.assertTrue(!level.getBlockState(floor.above()).is(Blocks.COBBLESTONE),
                    "the routine ran step 2 before the background step finished: " + run.reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 回放一个不存在的 routine:干净地失败,并在话里点名库里现有哪些——先存一条已知的当路标,再回放一个
     * 不存在的名字,断言失败回执同时提到两者。谁先跑都不依赖另一条用例:这条路标由本用例自己存。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_routine")
    public static void routine_unknown_name_fails_cleanly(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_routine_missing", new BlockPos(4, 2, 4), false);
        ToolRun save = call(companion, "routine", args(
                "action", "save",
                "name", "routine_known_probe",
                "description", "a known routine the failure can name",
                "steps", List.of("use ahead right"),
                "args", List.of()));
        ToolRun missing = call(companion, "routine", args("action", "run", "name", "routine_no_such_name"));

        succeedWhen(helper, () -> {
            helper.assertTrue(save.succeeded(), "the probe routine was not saved: " + save.reply());
            helper.assertTrue(missing.done(), "running an unknown routine never answered");
            helper.assertTrue(!missing.succeeded(), "an unknown routine did not cleanly fail: " + missing.reply());
            helper.assertTrue(missing.reply().contains("routine_no_such_name"),
                    "the failure does not name the unknown routine: " + missing.reply());
            helper.assertTrue(missing.reply().contains("routine_known_probe"),
                    "the failure does not name the routines that exist: " + missing.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
