package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.adapter.AdapterHandlers;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.PermissionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.List;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 真实物品右键管线:登记、装载、征询、执行一次与失败实际账。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class AdapterUseGameTests {
    @BeforeBatch(batch = "numen_adapter_use")
    public static void prepare(net.minecraft.server.level.ServerLevel level) {
        settleWorld(level, net.minecraft.world.Difficulty.PEACEFUL, NOON);
    }

    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_adapter_use")
    public static void adapter_use_waits_for_permission_and_never_falls_back(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        BlockPos target = helper.absolutePos(new BlockPos(5, 2, 5));
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        NumenPlayer body = spawnAt(helper, "gametest_adapter_use", new BlockPos(3, 2, 5), false);
        var owner = presentOwner(helper, body, "gametest_adapter_owner");
        body.getInventory().add(new ItemStack(Items.CLOCK));
        var store = PermissionStore.of(level.getServer(), owner.getUUID());
        store.setMode(body.getUUID(), Mode.ASK);
        int[] prepared = {0};
        int[] executed = {0};
        int[] scenario = {0};
        AdapterHandlers.UseHandler handler = new AdapterHandlers.UseHandler() {
            public Action action(NumenPlayer self, AdapterHandlers.UseContext context) {
                prepared[0]++;
                if (scenario[0] == 2) return null;
                return Action.breakBlock(target, self.level().getBlockState(target));
            }

            public List<String> act(NumenPlayer self, AdapterHandlers.UseContext context, Action authorized) {
                helper.assertTrue(authorized.pos().equals(target), "execution changed the authorized target");
                executed[0]++;
                self.level().setBlockAndUpdate(authorized.pos(), Blocks.AIR.defaultBlockState());
                if (scenario[0] == 3) throw new IllegalStateException("failed after changing target");
                return List.of("adapter changed the authorized target once");
            }
        };
        var root = Files.createTempDirectory("numen-adapter-use-test");
        var file = root.resolve("use.json");
        Files.writeString(file, """
                {"id":"gametest_adapter_use","priority":100000,"requires":["gametest_use"],
                 "useRoutes":[{"item":"minecraft:clock","intent":"gametest_use"}]}
                """);
        NumenPlugins.register("adapter_gametest", api -> {
            api.registerAdapterUse("gametest_use", handler);
            api.bundleAdapters(root);
        });
        // 玩家放置的目标触发出厂 break(placed),避免给测试另造许可策略。
        com.dwinovo.numen.permission.PlacedBlocks.of(level).record(target,
                new com.dwinovo.numen.permission.PlacedBlocks.Placer(owner.getUUID(), owner.getName().getString()));
        String code = "return numen.use.block(" + xyz(target) + ", {item = \"minecraft:clock\"})";
        ToolRun[] run = {lua(body, code)};
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(ConsentDesk.of(body).pending() != null, "no consent request"))
                .thenExecute(() -> helper.assertTrue(prepared[0] == 1 && executed[0] == 0
                        && level.getBlockState(target).is(Blocks.STONE), "acted before consent"))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(prepared[0] == 1 && executed[0] == 0, "reprepared or acted while waiting");
                    var pending = ConsentDesk.of(body).pending();
                    ConsentDesk.of(body).answer(pending.id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "authorized call has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(run[0].ranToTheEnd() && executed[0] == 1, "authorized action did not run once");
                    helper.assertTrue(run[0].receipt().contains("adapter changed the authorized target once")
                            && run[0].receipt().contains("stone -> air"), "missing actual facts: " + run[0].receipt());
                    scenario[0] = 1;
                    level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    store.setMode(body.getUUID(), Mode.OBSERVE);
                    run[0] = lua(body, code);
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "observe call has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue("denied".equals(run[0].kind()) && executed[0] == 1
                            && level.getBlockState(target).is(Blocks.STONE), "observe allowed adapter execution");
                    scenario[0] = 2;
                    store.setMode(body.getUUID(), Mode.BYPASS);
                    run[0] = lua(body, code);
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "missing action call has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(!run[0].ranToTheEnd() && executed[0] == 1
                            && run[0].receipt().contains("concrete Action"), "missing action fell back or ran");
                    scenario[0] = 3;
                    run[0] = lua(body, code);
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "throwing action has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(!run[0].ranToTheEnd() && executed[0] == 2
                            && run[0].receipt().contains("failed after changing target")
                            && run[0].receipt().contains("stone -> air"), "failure lost partial actual facts");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(executed[0] == 2, "terminal adapter action executed again");
                    scenario[0] = 0;
                    level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    com.dwinovo.numen.permission.PlacedBlocks.of(level).record(target,
                            new com.dwinovo.numen.permission.PlacedBlocks.Placer(owner.getUUID(), owner.getName().getString()));
                    store.setMode(body.getUUID(), Mode.ASK);
                    run[0] = lua(body, code);
                })
                .thenWaitUntil(() -> helper.assertTrue(ConsentDesk.of(body).pending() != null, "no second consent request"))
                .thenExecute(() -> {
                    helper.assertTrue(executed[0] == 2, "acted before the second answer");
                    var pending = ConsentDesk.of(body).pending();
                    ConsentDesk.of(body).answer(pending.id(), ConsentAnswer.Decision.DENY, "leave this target");
                })
                .thenWaitUntil(() -> helper.assertTrue(run[0].done(), "denied consent has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue("denied".equals(run[0].kind()) && executed[0] == 2
                            && level.getBlockState(target).is(Blocks.STONE)
                            && run[0].receipt().contains("leave this target"), "ignored owner's refusal");
                    try {
                        Files.delete(file);
                        Files.delete(root);
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException(error);
                    }
                    com.dwinovo.numen.adapter.AdapterManager.reload();
                    CompanionFactory.despawn(level.getServer(), body);
                    leave(owner);
                })
                .thenSucceed();
    }
}
