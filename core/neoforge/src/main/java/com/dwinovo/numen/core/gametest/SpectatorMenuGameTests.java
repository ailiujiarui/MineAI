package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.FakeConnection;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import com.dwinovo.numen.spectator.SpectatorMenuBridge;
import com.dwinovo.numen.spectator.SpectatorMenuFrame;
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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
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
}
