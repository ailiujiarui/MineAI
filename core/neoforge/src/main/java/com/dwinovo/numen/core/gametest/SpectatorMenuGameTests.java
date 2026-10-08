package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.core.gear.Wardrobe;
import com.dwinovo.numen.core.task.combat.AttackCompanionTask;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.FakeConnection;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.spectator.SpectatorMenuBridge;
import com.dwinovo.numen.spectator.SpectatorMenuFrame;
import com.dwinovo.numen.task.TaskState;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** Real server menus and craft actions verify the value-copy boundary and the display protocol. */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpectatorMenuGameTests {
    @BeforeBatch(batch = "numen_spectator_menu")
    public static void prepare(ServerLevel level) { settleWorld(level, Difficulty.PEACEFUL, NOON); }

    private static final class Watching implements AutoCloseable {
        final GameTestHelper helper;
        final ServerPlayer owner;
        final NumenPlayer body;
        final List<SpectatorMenuPayload> packets = new ArrayList<>();
        BlockPos table;

        Watching(GameTestHelper helper) {
            this.helper = helper;
            ServerLevel level = helper.getLevel();
            owner = new ServerPlayer(level.getServer(), level,
                    new GameProfile(UUID.randomUUID(), "gametest_viewer"), ClientInformation.createDefault());
            owner.connection = new ServerGamePacketListenerImpl(level.getServer(), new FakeConnection(), owner,
                    CommonListenerCookie.createInitial(owner.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket custom
                            && custom.payload() instanceof SpectatorMenuPayload menu) packets.add(menu);
                }
            };
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 17));
            body = spawnAt(helper, "gametest_watched", new BlockPos(3, 2, 3), false);
            body.setOwnerUuid(owner.getUUID());
            SpectatorMenuBridge.start(owner, body, 71);
        }

        SpectatorMenuPayload lastClosed() {
            return packets.stream().filter(p -> p.event() == SpectatorMenuPayload.CLOSE)
                    .reduce((a, b) -> b).orElseThrow();
        }

        SpectatorMenuPayload lastAction() {
            return packets.stream().filter(p -> p.event() == SpectatorMenuPayload.ACTION)
                    .reduce((a, b) -> b).orElseThrow();
        }

        long actionCount() {
            return packets.stream().filter(p -> p.event() == SpectatorMenuPayload.ACTION).count();
        }

        List<String> transfer(ContainerOps.Move... moves) {
            List<String> results = new ArrayList<>();
            try (var display = SpectatorMenuBridge.menuAction(body)) {
                ContainerOps ops = new ContainerOps();
                for (ContainerOps.Move move : moves) {
                    results.add(ops.step(move, body));
                    display.step();
                }
            }
            return results;
        }

        CraftOutcome craft(Item item, int count) {
            int made = 0;
            ApiError failed = null;
            if (table != null) {
                body.openMenu(helper.getLevel().getBlockState(table).getMenuProvider(helper.getLevel(), table));
            }
            try {
                // Several real atomic calls use the same open menu, as a program requesting several batches does.
                while (made < count) {
                    try {
                        CraftOps.Crafted round = CraftOps.craft(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item), count - made, body);
                        made += round.crafted();
                    } catch (ApiError error) {
                        failed = error;
                        break;
                    }
                }
            } finally {
                if (table != null) body.closeContainer();
            }
            return new CraftOutcome(made, failed);
        }

        void table() {
            table = helper.absolutePos(new BlockPos(5, 2, 3));
            helper.getLevel().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        }

        @Override
        public void close() {
            SpectatorMenuBridge.stop(owner);
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }
    }

    private record CraftOutcome(int crafted, ApiError failure) {}

    private static int count(SpectatorMenuFrame frame, Item item, int from, int to) {
        return frame.slots().subList(from, to).stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int total(SpectatorMenuFrame frame, Item item) {
        return count(frame, item, 0, frame.slots().size())
                + (frame.carried().is(item) ? frame.carried().getCount() : 0);
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void same_tick_table_craft_retains_materials_and_output(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.table();
            watch.body.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
            long tick = helper.getLevel().getGameTime();
            CraftOutcome result = watch.craft(Items.CHEST, 1);
            SpectatorMenuPayload closed = watch.lastClosed();
            helper.assertTrue(result.failure() == null && result.crafted() == 1, result.toString());
            helper.assertTrue(helper.getLevel().getGameTime() == tick, "craft introduced a body delay");
            helper.assertTrue(!closed.inventory() && closed.frame().slots().getFirst().is(Items.CHEST)
                            && count(closed.frame(), Items.OAK_PLANKS, 1, 10) == 8,
                    "closing frame lost the prepared recipe");
            helper.assertTrue(watch.packets.getFirst().event() == SpectatorMenuPayload.OPEN
                            && watch.packets.getLast().event() == SpectatorMenuPayload.CLOSE,
                    "same tick open/close events are missing");
            helper.assertTrue(watch.body.containerMenu == watch.body.inventoryMenu
                            && watch.body.getInventory().countItem(Items.CHEST) == 1
                            && watch.body.getInventory().countItem(Items.OAK_PLANKS) == 0,
                    "real craft or container cleanup changed");
            helper.assertTrue(watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                    "watching touched the owner's inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void hand_craft_has_explicit_short_inventory_lifetime(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            CraftOutcome result = watch.craft(Items.OAK_PLANKS, 8);
            SpectatorMenuPayload closed = watch.lastClosed();
            helper.assertTrue(result.failure() == null && result.crafted() == 8, result.toString());
            helper.assertTrue(closed.inventory() && closed.frame().slots().getFirst().is(Items.OAK_PLANKS)
                            && count(closed.frame(), Items.OAK_LOG, 1, 5) == 2,
                    "2x2 recipe frame was not retained");
            int packets = watch.packets.size();
            SpectatorMenuBridge.tick(watch.body);
            helper.assertTrue(watch.packets.size() == packets, "permanent InventoryMenu reopened a completed hand craft");
            helper.assertTrue(watch.body.getInventory().countItem(Items.OAK_LOG) == 0
                            && watch.body.getInventory().countItem(Items.OAK_PLANKS) == 8,
                    "hand crafting conservation changed");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void manual_inventory_stays_live_after_same_tick_hand_craft(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(9, new ItemStack(Items.OAK_LOG, 2));
            SpectatorMenuBridge.openInventory(watch.owner);
            long menuId = watch.packets.getLast().menuId();
            long tick = helper.getLevel().getGameTime();
            CraftOutcome result = watch.craft(Items.OAK_PLANKS, 8);
            helper.assertTrue(result.failure() == null && result.crafted() == 8, result.toString());
            helper.assertTrue(helper.getLevel().getGameTime() == tick, "hand craft introduced a body delay");
            helper.assertTrue(watch.packets.stream().allMatch(p -> p.menuId() == menuId)
                            && watch.packets.stream().filter(p -> p.event() == SpectatorMenuPayload.OPEN).count() == 1
                            && watch.packets.stream().noneMatch(p -> p.event() == SpectatorMenuPayload.CLOSE
                                    || p.event() == SpectatorMenuPayload.DISMISS),
                    "hand craft replaced or closed the manually opened inventory");
            helper.assertTrue(watch.packets.stream().anyMatch(p -> p.event() == SpectatorMenuPayload.UPDATE
                            && p.frame().slots().getFirst().is(Items.OAK_PLANKS)
                            && count(p.frame(), Items.OAK_LOG, 1, 5) > 0),
                    "manual inventory lost the real pre-take recipe snapshot");
            SpectatorMenuPayload completed = watch.packets.getLast();
            helper.assertTrue(completed.inventory() && completed.event() == SpectatorMenuPayload.UPDATE
                            && completed.frame().sameContents(SpectatorMenuFrame.capture(watch.body.inventoryMenu))
                            && completed.frame().slots().subList(0, 5).stream().allMatch(ItemStack::isEmpty)
                            && completed.frame().carried().isEmpty(),
                    "manual inventory retained a recipe preview instead of the real cleaned menu");
            helper.assertTrue(watch.body.containerMenu == watch.body.inventoryMenu
                            && watch.body.getInventory().countItem(Items.OAK_LOG) == 0
                            && watch.body.getInventory().countItem(Items.OAK_PLANKS) == 8
                            && total(completed.frame(), Items.OAK_PLANKS) == 8
                            && watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                    "hand crafting or the display changed inventory conservation");
            watch.body.getInventory().setItem(10, new ItemStack(Items.APPLE, 3));
            SpectatorMenuBridge.tick(watch.body);
            SpectatorMenuPayload updated = watch.packets.getLast();
            helper.assertTrue(updated.menuId() == menuId && updated.event() == SpectatorMenuPayload.UPDATE
                            && updated.frame().sameContents(SpectatorMenuFrame.capture(watch.body.inventoryMenu))
                            && total(updated.frame(), Items.APPLE) == 3,
                    "manual inventory stopped showing actual updates after hand crafting");
            SpectatorMenuBridge.closeInventory(watch.owner, menuId);
            helper.assertTrue(watch.packets.getLast().event() == SpectatorMenuPayload.DISMISS
                            && watch.packets.getLast().menuId() == menuId,
                    "the manually opened inventory could no longer be dismissed after crafting");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void repeated_craft_keeps_only_the_last_recipe_before_take(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.table();
            for (int i = 0; i < 16; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.IRON_INGOT, 64));
            for (int i = 16; i < 20; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.REDSTONE, 64));
            CraftOutcome result = watch.craft(Items.COMPASS, 256);
            List<SpectatorMenuPayload> recipes = watch.packets.stream()
                    .filter(p -> p.event() == SpectatorMenuPayload.UPDATE && p.frame().slots().getFirst().is(Items.COMPASS)).toList();
            SpectatorMenuFrame frozen = watch.lastClosed().frame();
            helper.assertTrue(result.failure() == null && result.crafted() == 256 && recipes.size() == 4,
                    result + " frames=" + recipes.size());
            helper.assertTrue(frozen.sameContents(recipes.getLast().frame())
                            && count(frozen, Items.COMPASS, 10, frozen.slots().size()) == 192,
                    "close did not keep the latest round's complete pre-take inventory");
            helper.assertTrue(count(recipes.getFirst().frame(), Items.COMPASS, 10, frozen.slots().size()) == 0,
                    "earlier frames alias the now-updated inventory");
            helper.assertTrue(watch.body.getInventory().countItem(Items.COMPASS) == 256
                            && watch.body.getInventory().countItem(Items.IRON_INGOT) == 0
                            && watch.body.getInventory().countItem(Items.REDSTONE) == 0,
                    "mass crafting conservation changed");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void full_inventory_preview_does_not_claim_a_successful_take(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.table();
            watch.body.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 9));
            for (int i = 1; i < 36; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            CraftOutcome result = watch.craft(Items.CHEST, 1);
            helper.assertTrue(result.crafted() == 0 && result.failure() != null
                            && result.failure().kind() == ErrorKind.FAILED
                            && result.failure().getMessage().contains("inventory is full"), result.toString());
            helper.assertTrue(watch.lastClosed().frame().slots().getFirst().is(Items.CHEST),
                    "a valid recipe preview was lost after QUICK_MOVE failed");
            helper.assertTrue(watch.body.getInventory().countItem(Items.CHEST) == 0
                            && watch.body.getInventory().countItem(Items.OAK_PLANKS) == 9
                            && watch.body.getInventory().countItem(Items.COBBLESTONE) == 35 * 64,
                    "failed take consumed, lost or created items");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void partial_craft_preserves_real_result_and_latest_valid_preview(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.table();
            // Redstone limits the second atomic call to one craft; a third call reports the real shortfall.
            for (int i = 0; i < 16; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.IRON_INGOT, 64));
            watch.body.getInventory().setItem(16, new ItemStack(Items.REDSTONE, 64));
            watch.body.getInventory().setItem(17, new ItemStack(Items.REDSTONE));
            CraftOutcome result = watch.craft(Items.COMPASS, 256);
            SpectatorMenuFrame frozen = watch.lastClosed().frame();
            helper.assertTrue(result.crafted() == 65 && result.failure() != null
                    && result.failure().kind() == ErrorKind.NO_MATERIAL, result.toString());
            helper.assertTrue(count(frozen, Items.COMPASS, 10, frozen.slots().size()) == 64
                            && count(frozen, Items.IRON_INGOT, 1, 10) == 4
                            && count(frozen, Items.REDSTONE, 1, 10) == 1,
                    "partial craft did not retain its final valid round");
            helper.assertTrue(watch.body.getInventory().countItem(Items.COMPASS) == 65
                            && watch.body.getInventory().countItem(Items.IRON_INGOT) == 764
                            && watch.body.getInventory().countItem(Items.REDSTONE) == 0,
                    "partial craft result diverged from actual inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void new_container_takes_over_manual_inventory_and_previous_close(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            SpectatorMenuBridge.openInventory(watch.owner);
            long manual = watch.packets.getLast().menuId();
            watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.threeRows(id, inventory),
                    Component.literal("First container")));
            SpectatorMenuPayload first = watch.packets.getLast();
            helper.assertTrue(first.event() == SpectatorMenuPayload.OPEN && !first.inventory()
                            && first.menuId() > manual && first.title().getString().equals("First container"),
                    "actual container did not immediately replace the requested inventory");
            watch.body.closeContainer();
            watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.oneRow(id, inventory),
                    Component.literal("Second container")));
            SpectatorMenuPayload second = watch.packets.getLast();
            helper.assertTrue(second.event() == SpectatorMenuPayload.OPEN && second.menuId() > first.menuId(),
                    "new open reused the previous display's identity");
            int packets = watch.packets.size();
            SpectatorMenuBridge.closeInventory(watch.owner, first.menuId());
            helper.assertTrue(watch.packets.size() == packets, "old client close dismissed the current menu");
            SpectatorMenuBridge.tick(watch.body);
            helper.assertTrue(watch.packets.getLast().menuId() == second.menuId(), "old display was emitted after the new open");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void menu_frame_copies_components_cursor_inventory_and_processing_data(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            SimpleContainer contents = new SimpleContainer(3);
            SimpleContainerData data = new SimpleContainerData(4);
            FurnaceMenu menu = new FurnaceMenu(9, watch.body.getInventory(), contents, data);
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            tool.setDamageValue(12);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Copied tool"));
            contents.setItem(0, tool);
            contents.setItem(1, new ItemStack(Items.COAL, 7));
            contents.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
            ItemStack carried = new ItemStack(Items.BREAD, 4);
            menu.setCarried(carried);
            watch.body.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 6));
            for (int i = 0; i < 4; i++) data.set(i, 40 + i);
            SpectatorMenuFrame frame = SpectatorMenuFrame.capture(menu);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                SpectatorMenuPayload sent = new SpectatorMenuPayload(71, watch.body.getUUID(), 5, 8,
                        SpectatorMenuPayload.CLOSE, ResourceLocation.withDefaultNamespace("furnace"),
                        Component.literal("Processing"), false, frame);
                SpectatorMenuPayload.STREAM_CODEC.encode(buffer, sent);
                SpectatorMenuPayload received = SpectatorMenuPayload.STREAM_CODEC.decode(buffer);
                helper.assertTrue(received.frame().sameContents(frame) && received.sessionId() == 71
                                && received.menuId() == 5 && received.revision() == 8
                                && received.title().getString().equals("Processing"),
                        "menu protocol lost an item component, cursor, data slot or display identity");
            } finally {
                buffer.release();
            }
            tool.setDamageValue(99);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Changed tool"));
            carried.setCount(1);
            contents.clearContent();
            watch.body.getInventory().clearContent();
            for (int i = 0; i < 4; i++) data.set(i, 0);
            helper.assertTrue(frame.slots().getFirst().getDamageValue() == 12
                            && frame.slots().getFirst().getHoverName().getString().equals("Copied tool")
                            && count(frame, Items.DIAMOND, 3, frame.slots().size()) == 6
                            && frame.carried().getCount() == 4 && frame.data().equals(List.of(40, 41, 42, 43)),
                    "value snapshot changed with real menu state");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void crafting_remainders_return_without_mutating_the_preview(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.table();
            for (int i = 0; i < 3; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.MILK_BUCKET));
            watch.body.getInventory().setItem(3, new ItemStack(Items.WHEAT, 3));
            watch.body.getInventory().setItem(4, new ItemStack(Items.SUGAR, 2));
            watch.body.getInventory().setItem(5, new ItemStack(Items.EGG));
            CraftOutcome result = watch.craft(Items.CAKE, 1);
            helper.assertTrue(result.failure() == null && result.crafted() == 1, result.toString());
            helper.assertTrue(count(watch.lastClosed().frame(), Items.MILK_BUCKET, 1, 10) == 3,
                    "bucket remainders replaced the frozen ingredients");
            helper.assertTrue(watch.body.getInventory().countItem(Items.CAKE) == 1
                            && watch.body.getInventory().countItem(Items.BUCKET) == 3
                            && watch.body.getInventory().countItem(Items.MILK_BUCKET) == 0,
                    "bucket remainders were lost or duplicated");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void same_tick_inventory_sort_records_each_completed_move(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 6));
            watch.body.getInventory().setItem(10, new ItemStack(Items.IRON_INGOT, 3));
            long tick = helper.getLevel().getGameTime();
            List<String> moved = watch.transfer(new ContainerOps.Move(9, 11, 2),
                    new ContainerOps.Move(10, 12, null));
            helper.assertTrue(moved.getFirst().contains("moved 2 oak_planks")
                            && moved.getLast().contains("moved 3 iron_ingot")
                            && helper.getLevel().getGameTime() == tick,
                    "display capture delayed or changed the real atomic transfers: " + moved);
            SpectatorMenuPayload action = watch.lastAction();
            List<SpectatorMenuFrame> frames = action.actionFrames();
            List<SpectatorMenuFrame> completed = frames.stream().filter(frame -> frame.carried().isEmpty()).toList();
            helper.assertTrue(watch.actionCount() == 1 && action.inventory() && action.closeAfterAction()
                            && frames.size() == 7 && completed.size() == 3,
                    "sorting did not preserve its before, real cursor clicks and completed moves");
            helper.assertTrue(frames.getFirst().slots().get(9).getCount() == 6
                            && frames.getFirst().slots().get(11).isEmpty()
                            && completed.get(1).slots().get(9).getCount() == 4
                            && completed.get(1).slots().get(11).getCount() == 2
                            && completed.get(1).slots().get(10).getCount() == 3
                            && frames.getLast().slots().get(10).isEmpty()
                            && frames.getLast().slots().get(12).getCount() == 3,
                    "completed sorting steps were overwritten by the final inventory");
            helper.assertTrue(frames.get(1).slots().get(9).isEmpty() && frames.get(1).carried().getCount() == 6
                            && frames.get(2).slots().get(11).getCount() == 1 && frames.get(2).carried().getCount() == 5
                            && frames.get(3).slots().get(11).getCount() == 2 && frames.get(3).carried().getCount() == 4,
                    "the cursor did not show the actual pickup, single-item drops and return");
            for (SpectatorMenuFrame frame : frames) {
                helper.assertTrue(total(frame, Items.OAK_PLANKS) == 6 && total(frame, Items.IRON_INGOT) == 3,
                        "a real click snapshot lost or duplicated items between slots and cursor");
            }
            helper.assertTrue(action.frame().sameContents(SpectatorMenuFrame.capture(watch.body.inventoryMenu))
                            && watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                    "the final display differs from the body or touched the owner");
            int packets = watch.packets.size();
            SpectatorMenuBridge.tick(watch.body);
            helper.assertTrue(watch.packets.size() == packets, "live inventory update overwrote the automatic display");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void hotbar_selection_is_a_visible_hand_change_without_moving_stacks(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            watch.body.getInventory().setItem(2, new ItemStack(Items.IRON_PICKAXE));
            watch.body.getInventory().selected = 0;
            Hotbar.hold(watch.body, 2);
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(action.actionFrames().size() == 2
                            && action.actionFrames().getFirst().selectedHotbar() == 0
                            && action.actionFrames().getLast().selectedHotbar() == 2
                            && action.frame().slots().get(36).is(Items.IRON_SWORD)
                            && action.frame().slots().get(38).is(Items.IRON_PICKAXE)
                            && watch.body.getMainHandItem().is(Items.IRON_PICKAXE),
                    "hotbar selection was omitted or rearranged items");
            Hotbar.hold(watch.body, 2);
            for (int i = 0; i < 9; i++) {
                if (watch.body.getInventory().getItem(i).isEmpty()) {
                    watch.body.getInventory().setItem(i, new ItemStack(Items.BREAD));
                }
            }
            Hotbar.hold(watch.body, -1);
            helper.assertTrue(watch.actionCount() == 1, "unchanged/unavailable empty-hand selection produced a false display");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void taking_from_backpack_and_swapping_hands_copy_the_real_slots(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().selected = 0;
            watch.body.getInventory().setItem(0, new ItemStack(Items.BREAD, 4));
            for (int i = 1; i < 9; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            watch.body.getInventory().setItem(9, new ItemStack(Items.IRON_SWORD));
            watch.body.getInventory().setItem(40, new ItemStack(Items.SHIELD));
            Hotbar.hold(watch.body, 9);
            SpectatorMenuPayload take = watch.lastAction();
            helper.assertTrue(take.actionFrames().getFirst().slots().get(9).is(Items.IRON_SWORD)
                            && take.frame().slots().get(9).is(Items.BREAD)
                            && take.frame().slots().get(9).getCount() == 4
                            && take.frame().slots().get(36).is(Items.IRON_SWORD),
                    "backpack-to-hand display does not describe the actual swap");
            Hotbar.hold(watch.body, Hotbar.OFFHAND);
            SpectatorMenuPayload swap = watch.lastAction();
            helper.assertTrue(swap.menuId() > take.menuId() && watch.actionCount() == 2
                            && swap.actionFrames().getFirst().slots().get(45).is(Items.SHIELD)
                            && swap.frame().slots().get(45).is(Items.IRON_SWORD)
                            && swap.frame().slots().get(36).is(Items.SHIELD)
                            && watch.body.getMainHandItem().is(Items.SHIELD)
                            && watch.body.getOffhandItem().is(Items.IRON_SWORD),
                    "new hand swap reused an old display or omitted the offhand");
            helper.assertTrue(take.frame().slots().get(36).is(Items.IRON_SWORD)
                            && watch.body.getInventory().countItem(Items.BREAD) == 4,
                    "new hand swap changed the old snapshot or lost the stowed item");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void wardrobe_wear_and_remove_show_armor_and_offhand(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            watch.body.getInventory().setItem(9, new ItemStack(Items.DIAMOND_HELMET));
            watch.body.getInventory().setItem(10, new ItemStack(Items.SHIELD));
            helper.assertTrue(Wardrobe.wear(watch.body, Items.DIAMOND_HELMET, "head").ok(), "helmet wear failed");
            SpectatorMenuPayload armor = watch.lastAction();
            helper.assertTrue(armor.actionFrames().getFirst().slots().get(5).is(Items.IRON_HELMET)
                            && armor.frame().slots().get(5).is(Items.DIAMOND_HELMET)
                            && count(armor.frame(), Items.IRON_HELMET, 9, 45) == 1,
                    "armor replacement lost the old piece or missed the worn slot");
            helper.assertTrue(Wardrobe.wear(watch.body, Items.SHIELD, Wardrobe.OFFHAND).ok(), "shield wear failed");
            helper.assertTrue(watch.lastAction().frame().slots().get(45).is(Items.SHIELD), "offhand wear was not displayed");
            helper.assertTrue(Wardrobe.remove(watch.body, Wardrobe.OFFHAND, null).ok(), "shield removal failed");
            helper.assertTrue(watch.lastAction().actionFrames().getFirst().slots().get(45).is(Items.SHIELD)
                            && watch.lastAction().frame().slots().get(45).isEmpty()
                            && watch.body.getInventory().countItem(Items.SHIELD) == 1,
                    "offhand removal was not copied or did not conserve the shield");
            helper.assertTrue(Wardrobe.remove(watch.body, "head", null).ok(), "helmet removal failed");
            helper.assertTrue(watch.actionCount() == 4 && watch.lastAction().frame().slots().get(5).isEmpty()
                            && watch.body.getInventory().countItem(Items.DIAMOND_HELMET) == 1,
                    "armor removal did not display its real final inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void partial_armor_removal_shows_only_completed_changes(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            watch.body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            for (int i = 0; i < 35; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            Wardrobe.Outcome result = Wardrobe.remove(watch.body, Wardrobe.ARMOR, null);
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(result.ok() && result.message().contains("inventory full, still wearing")
                            && result.change().removed().equals(List.of("iron_helmet (head)"))
                            && result.change().stillWorn().equals(List.of("iron_chestplate (chest)"))
                            && action.actionFrames().size() == 2 && action.frame().slots().get(5).isEmpty()
                            && action.frame().slots().get(6).is(Items.IRON_CHESTPLATE),
                    "partial removal fabricated a successful move for the piece that did not fit");
            helper.assertTrue(watch.body.getInventory().countItem(Items.IRON_HELMET) == 1
                            && watch.body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                            && count(action.frame(), Items.COBBLESTONE, 0, 46) == 35 * 64,
                    "partial removal changed conservation or its real result");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void full_inventory_and_invalid_moves_do_not_preview_a_success(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            for (int i = 0; i < 36; i++) watch.body.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            watch.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            SpectatorMenuFrame before = SpectatorMenuFrame.capture(watch.body.inventoryMenu);
            Wardrobe.Outcome failed = Wardrobe.remove(watch.body, "head", null);
            helper.assertTrue(!failed.ok() && failed.message().contains("inventory is full")
                    && failed.change().removed().isEmpty()
                    && failed.change().stillWorn().equals(List.of("iron_helmet (head)")), failed.message());
            helper.assertTrue(!Wardrobe.wear(watch.body, Items.DIAMOND_HELMET, "head").ok(), "missing armor unexpectedly equipped");
            List<String> invalid = watch.transfer(new ContainerOps.Move(90, 9, null));
            helper.assertTrue(invalid.getFirst().contains("OUT OF RANGE"),
                    "invalid transfer's existing real result changed: " + invalid);
            helper.assertTrue(watch.actionCount() == 0 && before.sameContents(SpectatorMenuFrame.capture(watch.body.inventoryMenu))
                            && watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                    "failed/no-op action opened a fictitious display or changed inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void nested_equipping_is_one_display_and_manual_inventory_stays_open(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(9, new ItemStack(Items.IRON_SWORD));
            SpectatorMenuBridge.openInventory(watch.owner);
            long menuId = watch.packets.getLast().menuId();
            helper.assertTrue(Wardrobe.wear(watch.body, Items.IRON_SWORD, Wardrobe.MAINHAND).ok(), "mainhand equip failed");
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(watch.actionCount() == 1 && action.menuId() == menuId && !action.closeAfterAction()
                            && action.actionFrames().size() == 2,
                    "nested hand change duplicated the display or auto-closed manual inventory");
            watch.body.getInventory().setItem(10, new ItemStack(Items.APPLE, 3));
            SpectatorMenuBridge.tick(watch.body);
            helper.assertTrue(watch.packets.getLast().event() == SpectatorMenuPayload.UPDATE
                            && watch.packets.getLast().menuId() == menuId
                            && watch.packets.getLast().frame().slots().get(10).getCount() == 3,
                    "manual inventory did not resume live updates");
            SpectatorMenuBridge.closeInventory(watch.owner, menuId);
            helper.assertTrue(watch.packets.getLast().event() == SpectatorMenuPayload.DISMISS,
                    "manual inventory close did not dismiss the action display");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void container_transfer_keeps_native_menu_and_allows_normal_close(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            SimpleContainer contents = new SimpleContainer(9);
            contents.setItem(0, new ItemStack(Items.DIAMOND, 5));
            watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ChestMenu(MenuType.GENERIC_9x1, id, inventory, contents, 1),
                    Component.literal("Sorting chest")));
            long menuId = watch.packets.getLast().menuId();
            List<String> moved = watch.transfer(new ContainerOps.Move(0, 9, 2));
            helper.assertTrue(moved.getFirst().contains("moved 2 diamond"), "real container transfer failed: " + moved);
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(!action.inventory() && !action.closeAfterAction() && action.menuId() == menuId
                            && action.title().getString().equals("Sorting chest")
                            && action.actionFrames().getFirst().slots().get(0).getCount() == 5
                            && action.frame().slots().get(0).getCount() == 3
                            && count(action.frame(), Items.DIAMOND, 9, 45) == 2,
                    "transfer replaced the chest with a backpack or lost the native steps");
            watch.body.closeContainer();
            helper.assertTrue(watch.lastClosed().menuId() == menuId
                            && watch.body.containerMenu == watch.body.inventoryMenu
                            && contents.countItem(Items.DIAMOND) == 3
                            && watch.body.getInventory().countItem(Items.DIAMOND) == 2,
                    "viewer playback delayed actual close or changed shared container contents");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void old_action_scope_cannot_replace_a_new_menu_or_session(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            try (var action = SpectatorMenuBridge.inventoryAction(watch.body)) {
                watch.body.getInventory().setItem(9, new ItemStack(Items.BREAD));
                action.step();
                watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.oneRow(id, inventory),
                        Component.literal("New menu")));
            }
            helper.assertTrue(watch.actionCount() == 0 && watch.packets.getLast().event() == SpectatorMenuPayload.OPEN
                            && watch.packets.getLast().title().getString().equals("New menu"),
                    "old inventory action replaced the newly opened native menu");
            watch.body.closeContainer();
            try (var action = SpectatorMenuBridge.inventoryAction(watch.body)) {
                watch.body.getInventory().setItem(10, new ItemStack(Items.APPLE));
                action.step();
                SpectatorMenuBridge.stop(watch.owner);
                SpectatorMenuBridge.start(watch.owner, watch.body, 72);
            }
            helper.assertTrue(watch.actionCount() == 0, "old scope emitted its frames into a new viewing session");
            Hotbar.hold(watch.body, 9);
            helper.assertTrue(watch.lastAction().sessionId() == 72, "current action retained the old viewing session");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void multiple_armor_removals_keep_intermediate_completed_slots(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            watch.body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            Wardrobe.Outcome result = Wardrobe.remove(watch.body, Wardrobe.ARMOR, null);
            List<SpectatorMenuFrame> frames = watch.lastAction().actionFrames();
            helper.assertTrue(result.ok() && result.change().removed().size() == 2
                            && result.change().stillWorn().isEmpty() && frames.size() == 3
                            && frames.getFirst().slots().get(5).is(Items.IRON_HELMET)
                            && frames.get(1).slots().get(5).isEmpty()
                            && frames.get(1).slots().get(6).is(Items.IRON_CHESTPLATE)
                            && frames.getLast().slots().get(6).isEmpty(),
                    "taking off several armor pieces collapsed into the final snapshot");
            for (SpectatorMenuFrame frame : frames) {
                helper.assertTrue(count(frame, Items.IRON_HELMET, 0, 46) == 1
                                && count(frame, Items.IRON_CHESTPLATE, 0, 46) == 1,
                        "intermediate armor snapshot lost or duplicated a piece");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void inventory_action_protocol_round_trip_preserves_frames_and_selected_hand(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().selected = 0;
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(13);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Streaming sword"));
            watch.body.getInventory().setItem(9, sword);
            Hotbar.hold(watch.body, 9);
            SpectatorMenuPayload sent = watch.lastAction();
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                SpectatorMenuPayload.STREAM_CODEC.encode(buffer, sent);
                SpectatorMenuPayload received = SpectatorMenuPayload.STREAM_CODEC.decode(buffer);
                helper.assertTrue(received.event() == SpectatorMenuPayload.ACTION && received.closeAfterAction()
                                && received.actionFrames().size() == 2 && received.frame().sameContents(sent.frame()),
                        "action codec lost the playback sequence or auto-close flag");
                for (int i = 0; i < sent.actionFrames().size(); i++) {
                    helper.assertTrue(received.actionFrames().get(i).sameContents(sent.actionFrames().get(i)),
                            "action codec changed frame " + i);
                }
            } finally {
                buffer.release();
            }
            sword.setDamageValue(99);
            watch.body.getInventory().clearContent();
            watch.body.getInventory().selected = 8;
            helper.assertTrue(sent.actionFrames().getFirst().slots().get(9).getDamageValue() == 13
                            && sent.frame().slots().get(36).getDamageValue() == 13
                            && sent.frame().slots().get(36).getHoverName().getString().equals("Streaming sword")
                            && sent.frame().selectedHotbar() == 0,
                    "real item/selection mutations changed stored action frames");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void freeing_main_hand_displays_selection_and_unwatched_actions_stay_normal(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().selected = 0;
            watch.body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            Wardrobe.Outcome result = Wardrobe.remove(watch.body, Wardrobe.MAINHAND, null);
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(result.ok() && result.message().contains("switched to an empty hotbar slot")
                            && action.actionFrames().getFirst().selectedHotbar() == 0
                            && action.frame().selectedHotbar() == 1
                            && action.frame().slots().get(36).is(Items.IRON_SWORD)
                            && watch.body.getMainHandItem().isEmpty(),
                    "freeing the main hand moved the sword or missed the selection-only change");
            SpectatorMenuBridge.stop(watch.owner);
            int packets = watch.packets.size();
            Hotbar.hold(watch.body, 0);
            helper.assertTrue(watch.body.getMainHandItem().is(Items.IRON_SWORD) && watch.packets.size() == packets,
                    "unwatched inventory actions acquired display work or failed to equip");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void combat_auto_shield_pickup_records_backpack_and_offhand(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().selected = 0;
            watch.body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            watch.body.getInventory().setItem(9, new ItemStack(Items.SHIELD));
            var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 3));
            zombie.setNoAi(true);
            zombie.setTarget(watch.body);
            AttackCompanionTask attack = new AttackCompanionTask(watch.body,
                    new AttackTaskRecord("watch-shield", "watch-shield", helper.getLevel().getGameTime() + 200,
                            List.of(zombie.getId()), false));
            attack.start(watch.body);
            attack.tick(watch.body);
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(action.actionFrames().getFirst().slots().get(9).is(Items.SHIELD)
                            && action.actionFrames().getFirst().slots().get(45).isEmpty()
                            && action.frame().slots().get(9).isEmpty()
                            && action.frame().slots().get(45).is(Items.SHIELD)
                            && watch.body.getOffhandItem().is(Items.SHIELD),
                    "automatic combat shield pickup was not captured atomically");
            helper.assertTrue(count(action.frame(), Items.SHIELD, 0, 46) == 1,
                    "combat shield display lost or duplicated the real shield");
            attack.result(TaskState.CANCELLED);
            zombie.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void equipping_with_a_container_open_still_displays_armor_and_hands(GameTestHelper helper) {
        try (Watching watch = new Watching(helper)) {
            watch.body.getInventory().setItem(9, new ItemStack(Items.IRON_HELMET));
            watch.body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            watch.body.getInventory().setItem(40, new ItemStack(Items.SHIELD));
            watch.body.getInventory().selected = 0;
            watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.oneRow(id, inventory),
                    Component.literal("Equipment chest")));
            var actual = watch.body.containerMenu;
            long containerId = watch.packets.getLast().menuId();
            helper.assertTrue(Wardrobe.wear(watch.body, Items.IRON_HELMET, "head").ok(), "helmet wear failed");
            SpectatorMenuPayload armor = watch.lastAction();
            helper.assertTrue(armor.inventory() && armor.menuId() > containerId
                            && armor.actionFrames().getFirst().slots().get(5).isEmpty()
                            && armor.frame().slots().get(5).is(Items.IRON_HELMET)
                            && watch.body.containerMenu == actual,
                    "body equipment used a native menu missing armor slots or closed the real chest");
            Hotbar.hold(watch.body, Hotbar.OFFHAND);
            helper.assertTrue(watch.lastAction().inventory()
                            && watch.lastAction().frame().slots().get(45).is(Items.IRON_SWORD)
                            && watch.lastAction().frame().slots().get(36).is(Items.SHIELD)
                            && watch.body.containerMenu == actual,
                    "hand swap with a real container open omitted the hand destination");
            long handDisplay = watch.lastAction().menuId();
            int packets = watch.packets.size();
            SpectatorMenuBridge.resumeMenu(watch.owner, armor.menuId());
            helper.assertTrue(watch.packets.size() == packets, "old display timer restored a container over a new action");
            SpectatorMenuBridge.resumeMenu(watch.owner, handDisplay);
            SpectatorMenuPayload resumed = watch.packets.getLast();
            helper.assertTrue(resumed.event() == SpectatorMenuPayload.OPEN && !resumed.inventory()
                            && resumed.menuId() > handDisplay && watch.body.containerMenu == actual
                            && resumed.frame().sameContents(SpectatorMenuFrame.capture(actual)),
                    "completed backpack display did not restore the still-open native container's live values");
            packets = watch.packets.size();
            SpectatorMenuBridge.resumeMenu(watch.owner, handDisplay);
            helper.assertTrue(watch.packets.size() == packets, "duplicate resume restarted the native display");
            watch.body.closeContainer();
            helper.assertTrue(!watch.lastClosed().inventory() && watch.lastClosed().frame().slots().size() == 45,
                    "real chest closing reused a backpack frame or menu type");
        }
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 40, batch = "numen_spectator_menu")
    public static void permission_refusal_displays_only_the_moves_that_really_happened(GameTestHelper helper) {
        Watching watch = new Watching(helper);
        SimpleContainer contents = new SimpleContainer(9);
        contents.setItem(0, new ItemStack(Items.DIAMOND, 5));
        watch.body.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 4));
        watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ChestMenu(MenuType.GENERIC_9x1, id, inventory, contents, 1),
                Component.literal("Read-only permissions")));
        Permission.setMode(watch.body, Mode.OBSERVE);
        ToolRun moves = lua(watch.body, "numen.gui.move(9, 10)\nnumen.gui.move(0, 11, {count = 2})");
        steps(helper).thenWaitUntil(() -> {
            helper.assertTrue(moves.done(), "atomic moves have not replied");
            helper.assertTrue(!moves.succeeded() && moves.outcome().contains("did not take diamond")
                            && "denied".equals(moves.kind()),
                    "viewer capture changed the permission refusal result: " + moves.outcome());
            SpectatorMenuPayload action = watch.lastAction();
            helper.assertTrue(watch.actionCount() == 1 && action.actionFrames().size() == 3 && !action.inventory()
                            && action.actionFrames().get(1).carried().is(Items.OAK_PLANKS)
                            && action.actionFrames().get(1).carried().getCount() == 4
                            && action.frame().slots().get(0).getCount() == 5
                            && action.frame().slots().get(9).isEmpty()
                            && action.frame().slots().get(10).getCount() == 4
                            && action.frame().slots().get(11).isEmpty()
                            && contents.countItem(Items.DIAMOND) == 5
                            && watch.body.getInventory().countItem(Items.DIAMOND) == 0,
                    "refused transfer appeared in playback or touched shared container items");
            for (SpectatorMenuFrame frame : action.actionFrames()) {
                helper.assertTrue(total(frame, Items.OAK_PLANKS) == 4 && total(frame, Items.DIAMOND) == 5,
                        "permission-controlled move lost items in a cursor snapshot");
            }
        }).thenExecute(() -> {
            watch.body.closeContainer();
            watch.close();
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_spectator_menu")
    public static void lua_gui_moves_display_each_real_atomic_call_and_cursor(GameTestHelper helper) {
        Watching watch = new Watching(helper);
        watch.body.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 6));
        watch.body.getInventory().setItem(10, new ItemStack(Items.IRON_INGOT, 3));
        ToolRun moves = lua(watch.body, "numen.gui.move(9, 11, {count = 2})\nnumen.gui.move(10, 12)");
        steps(helper).thenWaitUntil(() -> {
            helper.assertTrue(moves.done() && moves.succeeded(), "real Lua gui moves did not finish: " + moves.outcome());
            List<SpectatorMenuPayload> actions = watch.packets.stream()
                    .filter(packet -> packet.event() == SpectatorMenuPayload.ACTION).toList();
            helper.assertTrue(actions.size() == 2 && actions.getFirst().menuId() < actions.getLast().menuId()
                            && actions.getFirst().closeAfterAction() && actions.getLast().closeAfterAction()
                            && actions.getFirst().actionFrames().size() == 5 && actions.getLast().actionFrames().size() == 3,
                    "each real atomic call did not own its current before/cursor/after display");
            helper.assertTrue(actions.getFirst().frame().slots().get(9).getCount() == 4
                            && actions.getFirst().frame().slots().get(11).getCount() == 2
                            && actions.getFirst().frame().slots().get(10).getCount() == 3
                            && actions.getLast().frame().slots().get(10).isEmpty()
                            && actions.getLast().frame().slots().get(12).getCount() == 3
                            && actions.getLast().frame().sameContents(SpectatorMenuFrame.capture(watch.body.inventoryMenu)),
                    "later Lua calls overwrote earlier values or failed to show the real final inventory");
            for (SpectatorMenuPayload action : actions) {
                helper.assertTrue(action.actionFrames().stream().anyMatch(frame -> !frame.carried().isEmpty()),
                        "the real pickup cursor was not shown");
                for (SpectatorMenuFrame frame : action.actionFrames()) {
                    helper.assertTrue(total(frame, Items.OAK_PLANKS) == 6 && total(frame, Items.IRON_INGOT) == 3,
                            "a Lua gui snapshot lost or duplicated items between slots and cursor");
                }
            }
            helper.assertTrue(watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                    "Lua gui viewing touched the owner's actual inventory");
        }).thenExecute(watch::close).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_spectator_menu")
    public static void lua_gui_quick_put_and_take_use_the_same_native_display_bridge(GameTestHelper helper) {
        Watching watch = new Watching(helper);
        SimpleContainer contents = new SimpleContainer(9);
        contents.setItem(0, new ItemStack(Items.DIAMOND, 5));
        watch.body.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                new ChestMenu(MenuType.GENERIC_9x1, id, inventory, contents, 1), Component.literal("Lua storage")));
        long menuId = watch.packets.getLast().menuId();
        ToolRun[] step = new ToolRun[1];
        steps(helper).thenExecute(() -> step[0] = lua(watch.body, "numen.gui.quick(0)"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step[0].done() && step[0].succeeded(), "real gui quick failed: " + step[0].outcome());
                    SpectatorMenuPayload action = watch.lastAction();
                    helper.assertTrue(watch.actionCount() == 1 && !action.inventory() && !action.closeAfterAction()
                                    && action.menuId() == menuId && action.actionFrames().size() == 2
                                    && action.actionFrames().getFirst().slots().get(0).getCount() == 5
                                    && action.frame().slots().get(0).isEmpty()
                                    && watch.body.getInventory().countItem(Items.DIAMOND) == 5,
                            "gui quick bypassed the native display bridge or changed the actual result");
                }).thenExecute(() -> step[0] = lua(watch.body, "numen.gui.put(\"minecraft:diamond\", 2)"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step[0].done() && step[0].succeeded() && Long.valueOf(2).equals(step[0].value()),
                            "real gui put did not report the moved count: " + step[0].outcome());
                    SpectatorMenuPayload action = watch.lastAction();
                    helper.assertTrue(watch.actionCount() == 2 && action.menuId() == menuId && !action.inventory()
                                    && action.actionFrames().getFirst().slots().get(0).isEmpty()
                                    && action.frame().slots().get(0).getCount() == 2
                                    && contents.countItem(Items.DIAMOND) == 2
                                    && watch.body.getInventory().countItem(Items.DIAMOND) == 3,
                            "gui put bypassed the shared bridge or changed its native container result");
                }).thenExecute(() -> step[0] = lua(watch.body, "numen.gui.take(\"minecraft:diamond\", 1)"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step[0].done() && step[0].succeeded() && Long.valueOf(1).equals(step[0].value()),
                            "real gui take did not report the moved count: " + step[0].outcome());
                    SpectatorMenuPayload action = watch.lastAction();
                    helper.assertTrue(watch.actionCount() == 3 && action.menuId() == menuId && !action.inventory()
                                    && !action.closeAfterAction()
                                    && action.actionFrames().getFirst().slots().get(0).getCount() == 2
                                    && action.frame().slots().get(0).getCount() == 1
                                    && contents.countItem(Items.DIAMOND) == 1
                                    && watch.body.getInventory().countItem(Items.DIAMOND) == 4,
                            "gui take bypassed the shared bridge or changed its native container result");
                    for (SpectatorMenuPayload packet : watch.packets) {
                        for (SpectatorMenuFrame frame : packet.actionFrames()) {
                            helper.assertTrue(total(frame, Items.DIAMOND) == 5,
                                    "quick/put/take lost items between container, backpack and cursor");
                        }
                    }
                    helper.assertTrue(watch.owner.getInventory().countItem(Items.DIAMOND) == 17,
                            "native Lua gui viewing modified the owner inventory");
                }).thenExecute(() -> {
                    watch.body.closeContainer();
                    helper.assertTrue(watch.lastClosed().menuId() == menuId
                                    && watch.body.containerMenu == watch.body.inventoryMenu,
                            "native Lua actions changed the actual closing lifecycle");
                    watch.close();
                }).thenSucceed();
    }
}
