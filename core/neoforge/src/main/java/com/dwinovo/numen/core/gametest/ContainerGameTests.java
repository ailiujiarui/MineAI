package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 容器:{@code numen.use.block} 右键打开、{@code numen.gui.view} 看格子、{@code numen.gui.quick} 整叠挪到另一边或 {@code numen.gui.move} 放进指定的一格、
 * {@code numen.gui.close} 合上。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ContainerGameTests {

    /** 容器批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_container")
    public static void prepareContainerBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 模型取箱子里的东西就是这四步:右键打开箱子,看一眼格子,把第 0 格整叠拿进背包,合上。每一步都照模型的
     * 样子从工具入口调;五颗钻石到了她身上,箱子空了,界面合上。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void take_from_a_chest_open_look_move_close(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_looter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        // 动作放 thenExecute、断言放 thenWaitUntil:原版序列里 thenExecute 的断言失败后,后面的步骤照样在同一刻
        // 跑下去,报出来的是最后一个失败;等在 thenWaitUntil 里,哪一步没过就停在哪一步、报哪一步
        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && step.get().reply().contains("{\"index\":0,\"side\":\"container\",\"item\":\"minecraft:diamond\",\"count\":5}"),
                        "gui view does not show the diamonds in slot 0: " + step.get().reply()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.quick(0)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.getInventory().countItem(Items.DIAMOND) == 5
                                && ((ChestBlockEntity) helper.getLevel().getBlockEntity(chest)).isEmpty(),
                        "the diamonds did not move from the chest into her inventory: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().succeeded()
                                && companion.containerMenu == companion.inventoryMenu,
                        "gui close did not close the chest: " + step.get().reply()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 没开方块界面时照真实槽号读取背包、2×2、装备和光标;查询不搬动任何物品。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void inspect_own_inventory_reports_slots_without_mutation(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_inventory_reader", new BlockPos(3, 2, 4), false);
        var inventory = companion.getInventory();
        inventory.clearContent();
        inventory.setItem(9, new ItemStack(Items.DIAMOND, 5));
        inventory.setItem(0, new ItemStack(Items.BREAD, 3));
        inventory.selected = 2;
        companion.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        companion.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        companion.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        companion.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        companion.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        var menu = companion.inventoryMenu;
        menu.getSlot(1).set(new ItemStack(Items.OAK_LOG, 2));
        menu.getSlot(4).set(new ItemStack(Items.COBBLESTONE, 3));
        menu.setCarried(new ItemStack(Items.APPLE, 4));
        List<ItemStack> before = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
        ItemStack cursorBefore = menu.getCarried().copy();
        AtomicReference<ToolRun> query = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> query.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(query.get().done() && query.get().succeeded(),
                            "gui view failed on the own inventory: " + query.get().reply());
                    var contents = dataIn(query.get().reply());
                    helper.assertTrue(contents.get("menu").getAsString().equals("InventoryMenu"),
                            "the query does not identify the own inventory: " + contents);
                    Map<Integer, JsonObject> slots = new java.util.HashMap<>();
                    for (var value : contents.getAsJsonArray("slots")) {
                        var slot = value.getAsJsonObject();
                        slots.put(slot.get("index").getAsInt(), slot);
                    }
                    Map<Integer, ItemStack> expected = Map.of(
                            1, new ItemStack(Items.OAK_LOG, 2),
                            4, new ItemStack(Items.COBBLESTONE, 3),
                            5, new ItemStack(Items.IRON_HELMET),
                            6, new ItemStack(Items.IRON_CHESTPLATE),
                            7, new ItemStack(Items.IRON_LEGGINGS),
                            8, new ItemStack(Items.IRON_BOOTS),
                            9, new ItemStack(Items.DIAMOND, 5),
                            36, new ItemStack(Items.BREAD, 3),
                            45, new ItemStack(Items.SHIELD));
                    for (var entry : expected.entrySet()) {
                        var slot = slots.get(entry.getKey());
                        helper.assertTrue(slot != null && slot.get("item").getAsString().equals(
                                        net.minecraft.core.registries.BuiltInRegistries.ITEM
                                                .getKey(entry.getValue().getItem()).toString())
                                        && slot.get("count").getAsInt() == entry.getValue().getCount()
                                        && slot.get("side").getAsString().equals(entry.getKey() == 1 || entry.getKey() == 4
                                                ? "grid" : "you"),
                                "the query lost a grid, backpack, hotbar or equipment slot " + entry.getKey()
                                        + ": " + contents);
                    }
                    helper.assertTrue(slots.get(0).get("side").getAsString().equals("result")
                                    && slots.get(0).get("output").getAsBoolean() && !slots.get(0).has("item")
                                    && slots.get(2).get("side").getAsString().equals("grid") && !slots.get(2).has("item")
                                    && slots.get(3).get("side").getAsString().equals("grid") && !slots.get(3).has("item"),
                            "the query lost the result or empty 2x2 grid slots: " + contents);
                    helper.assertTrue(contents.get("cursor").getAsString().equals("apple x4"),
                            "the query lost cursor contents: " + contents);
                    helper.assertTrue(companion.containerMenu == menu && inventory.selected == 2,
                            "the query changed the menu or selected hotbar slot");
                    for (int i = 0; i < before.size(); i++) {
                        helper.assertTrue(ItemStack.matches(before.get(i), menu.getSlot(i).getItem()),
                                "the query changed inventory/grid slot " + i);
                    }
                    helper.assertTrue(ItemStack.matches(cursorBefore, menu.getCarried()), "the query changed the cursor");
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 没开任何容器就搬东西:她自己的背包界面里没有箱子那一段,越界的格子号如实报出来,什么都不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void transfer_with_no_container_open_moves_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_shuffler", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        ToolRun transfer = lua(companion, "numen.gui.quick(90)");

        succeedWhen(helper, () -> {
            helper.assertTrue(transfer.done(), "transfer has not finished");
            helper.assertTrue(transfer.outcome().contains("OUT OF RANGE"),
                    "the reply does not say the slot is out of range: " + transfer.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5, "the diamonds moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 没开着任何方块界面时关界面:不算错,交回 false——没有开着的界面可关。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_container")
    public static void close_gui_with_nothing_open_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_tidy", new BlockPos(3, 2, 3), false);
        ToolRun close = lua(companion, "numen.gui.close()");

        succeedWhen(helper, () -> {
            helper.assertTrue(close.succeeded() && Boolean.FALSE.equals(close.value()),
                    "the reply does not say nothing was open: " + close.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 往塞满圆石的箱子里存钻石:打开箱子,把背包里那叠钻石往箱子那一段送。箱子一格都放不下,这一步的回执说没搬动、
     * 那边满了;钻石还在她身上,箱子原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void transfer_into_a_full_chest_says_nothing_moved(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(5, 2, 4));
        helper.getLevel().setBlockAndUpdate(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        ChestBlockEntity box = (ChestBlockEntity) helper.getLevel().getBlockEntity(chest);
        for (int i = 0; i < box.getContainerSize(); i++) {
            box.setItem(i, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64));
        }
        NumenPlayer companion = spawnAt(helper, "gametest_depositor", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        AtomicReference<ToolRun> step = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.quick(" + menuSlotOf(companion, Items.DIAMOND) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done()
                                && step.get().outcome().contains("didn't move"),
                        "the reply does not say the diamonds stayed: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 5
                                && box.countItem(Items.COBBLESTONE) == box.getContainerSize() * 64
                                && box.countItem(Items.DIAMOND) == 0,
                        "something moved between her and the full chest"))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 从箱子里按数量拿:五颗钻石里拿两颗放进她背包的一个空格,正好两颗过来,箱子里剩三颗。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_container")
    public static void transfer_an_exact_count_into_a_slot(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 4), 5);
        ChestBlockEntity box = (ChestBlockEntity) helper.getLevel().getBlockEntity(chest);
        NumenPlayer companion = spawnAt(helper, "gametest_counter", new BlockPos(3, 2, 4), false);
        AtomicReference<ToolRun> step = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(chest) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof ChestMenu,
                        "the chest did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.move(0, " + menuSlotOf(companion, Items.AIR) + ", {count = 2})")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && step.get().outcome().contains("moved 2 diamond"),
                        "the reply does not say two diamonds moved: " + step.get().outcome()))
                .thenWaitUntil(() -> helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 2
                                && box.countItem(Items.DIAMOND) == 3,
                        "she has " + companion.getInventory().countItem(Items.DIAMOND) + " and the chest "
                                + box.countItem(Items.DIAMOND)))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.close()")))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 她打开的界面里,她自己背包那一段第一个装着 {@code item} 的格子号(AIR = 第一个空格)——模型从 numen.gui.view 读到的就是它。 */
    private static int menuSlotOf(NumenPlayer companion, net.minecraft.world.item.Item item) {
        var slots = companion.containerMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.container == companion.getInventory()
                    && (item == Items.AIR ? slot.getItem().isEmpty() : slot.getItem().is(item))) {
                return i;
            }
        }
        throw new IllegalStateException("no slot of hers holds " + item);
    }
}
