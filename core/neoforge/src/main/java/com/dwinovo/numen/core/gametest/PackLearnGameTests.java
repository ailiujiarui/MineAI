package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.agent.adapter.AdapterSpec;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 学习型适配:开一次机器就把这个菜单连同槽位角色学到 {@code auto-learned.json},并让
 * {@code inspect_gui} 对任何菜单都带上角色标注。
 *
 * <p>题面用的是整合包里真实存在、能无头打开的机器:Mekanism 的某台没被声明过的机器验证"学到了",
 * AE2 的压印器(没有专门的 GUI 处理器)验证"通用标注读得出 [input]/[output]"。目标模组不在场时
 * 直接成功返回(跳过)。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackLearnGameTests {

    @BeforeBatch(batch = "numen_pack_learn")
    public static void prepareLearnBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 富集仓、粉碎机这些在插件里声明过的机器不算题面——这里挑一台没声明的 Mekanism 机器,开一次,
     * 断言自学习把它的菜单学进了生效适配器,且槽位角色表里有 input。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_pack_learn")
    public static void opening_a_machine_learns_its_menu_and_slots(GameTestHelper helper) {
        Block machine = BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse("mekanism:osmium_compressor")).orElse(null);
        if (machine == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(5, 2, 4));
        level.setBlockAndUpdate(at, machine.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_learner", new BlockPos(3, 2, 4), false);

        ToolRun open = command(companion, "use block right "
                + at.getX() + " " + at.getY() + " " + at.getZ());

        helper.succeedWhen(() -> {
            helper.assertTrue(open.done() && open.succeeded(),
                    "interact_at did not open the machine: " + open.outcome());
            AdapterSpec.MachineSpec learned = AdapterManager.registry()
                    .machineForMenu("mekanism:osmium_compressor").orElse(null);
            helper.assertTrue(learned != null,
                    "opening the machine did not learn it into the adapters");
            helper.assertTrue(!learned.slots("input").isEmpty(),
                    "the learned machine has no input slots: " + learned.slots());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 铁熔炉没有专门的 GUI 处理器,槽类名自带语义({@code SlotIronFurnaceInput/Fuel/...}),所以
     * {@code inspect_gui} 走通用路径时,通用分类器要把它标成 [input]/[fuel]。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_pack_learn")
    public static void inspect_gui_marks_slot_roles_for_any_machine(GameTestHelper helper) {
        Block machine = BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse("ironfurnaces:iron_furnace")).orElse(null);
        if (machine == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(5, 2, 4));
        level.setBlockAndUpdate(at, machine.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_reader", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> step.set(command(companion, "use block right "
                        + at.getX() + " " + at.getY() + " " + at.getZ())))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu != companion.inventoryMenu,
                        "the machine did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(command(companion, "use gui")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().reply().contains("[input]")
                                && step.get().reply().contains("[fuel]"),
                        "inspect_gui shows no generic slot roles: " + step.get().reply()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }
}
