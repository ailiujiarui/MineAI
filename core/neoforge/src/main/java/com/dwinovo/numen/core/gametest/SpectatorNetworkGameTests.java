package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import com.dwinovo.numen.network.payload.SpectatorStatePayload;
import com.dwinovo.numen.spectator.SpectatorMenuFrame;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.dwinovo.numen.core.gametest.GameTestKit.spawnAt;

/** Large viewing packets cross the real fragment routes with the receiving world's dynamic registries. */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpectatorNetworkGameTests {
    // One component stays below NBT's string limit; multiple genuine stacks exceed a client packet.
    private static final String LARGE_NAME = "stream-item-" + "x".repeat(50_000);

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_spectator_network")
    public static void fragmented_menu_actions_keep_registry_components_and_value_snapshots(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_net_menu", new BlockPos(3, 2, 3), false);
        try {
            for (int slot = 0; slot < 20; slot++) body.getInventory().setItem(slot, largePick(helper, slot));
            SimpleContainer contents = new SimpleContainer(3);
            contents.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            contents.setItem(1, new ItemStack(Items.COAL, 7));
            contents.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
            SimpleContainerData data = new SimpleContainerData(4);
            data.set(0, 100);
            data.set(1, 200);
            data.set(2, 30);
            data.set(3, 80);
            Component title = Component.literal("Fragmented processing");
            body.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                    new FurnaceMenu(id, inventory, contents, data), title));
            FurnaceMenu menu = (FurnaceMenu) body.containerMenu;
            SpectatorMenuFrame before = SpectatorMenuFrame.capture(menu);
            menu.clicked(3, 0, ClickType.PICKUP, body);
            SpectatorMenuFrame pickup = SpectatorMenuFrame.capture(menu);
            menu.clicked(4, 0, ClickType.PICKUP, body);
            SpectatorMenuFrame swap = SpectatorMenuFrame.capture(menu);
            menu.clicked(3, 0, ClickType.PICKUP, body);
            body.getInventory().selected = 8;
            data.set(2, 31);
            SpectatorMenuFrame after = SpectatorMenuFrame.capture(menu);
            helper.assertTrue(pickup.carried().getDamageValue() == 9 && swap.carried().getDamageValue() == 10
                            && after.carried().isEmpty() && after.slots().get(3).getDamageValue() == 10
                            && after.slots().get(4).getDamageValue() == 9,
                    "the real furnace inventory did not pick up, swap, and return the two different tools");
            List<SpectatorMenuFrame> frames = List.of(before, pickup, swap, after);
            SpectatorMenuPayload sent = new SpectatorMenuPayload(71, body.getUUID(), 77, 12,
                    SpectatorMenuPayload.ACTION, ResourceLocation.withDefaultNamespace("furnace"), title,
                    false, after, frames, false);
            CustomPacketPayload whole = crossFragments(helper, SpectatorMenuPayload.STREAM_CODEC, sent);
            helper.assertTrue(whole instanceof SpectatorMenuPayload, "the registered menu route did not decode a menu");
            SpectatorMenuPayload received = (SpectatorMenuPayload) whole;
            helper.assertTrue(received.type().equals(SpectatorMenuPayload.TYPE)
                            && received.sessionId() == sent.sessionId() && received.target().equals(sent.target())
                            && received.menuId() == sent.menuId() && received.revision() == sent.revision()
                            && received.event() == SpectatorMenuPayload.ACTION
                            && received.menuType().equals(sent.menuType()) && received.title().equals(title)
                            && !received.inventory() && !received.closeAfterAction(),
                    "fragmentation changed the menu session, identity, title, or action flags");
            helper.assertTrue(received.frame().sameContents(after) && received.actionFrames().size() == frames.size(),
                    "fragmentation lost the final menu state or action frames");
            for (int index = 0; index < frames.size(); index++) {
                helper.assertTrue(received.actionFrames().get(index).sameContents(frames.get(index)),
                        "fragmentation changed slots, cursor, processing data, or hotbar in action frame " + index);
            }
            assertEnchanted(helper, received.actionFrames().get(1).carried());
            assertEnchanted(helper, received.actionFrames().get(2).carried());
            helper.assertTrue(received.actionFrames().get(0).data().equals(List.of(100, 200, 30, 80))
                            && received.frame().data().equals(List.of(100, 200, 31, 80))
                            && received.frame().selectedHotbar() == 8,
                    "processing progress and selected hotbar did not survive the wire");
            body.getInventory().clearContent();
            contents.clearContent();
            menu.setCarried(ItemStack.EMPTY);
            data.set(2, 0);
            helper.assertTrue(received.frame().sameContents(after), "live menu cleanup changed the decoded value snapshot");
            received.frame().slots().get(3).set(DataComponents.CUSTOM_NAME, Component.literal("receiver-only"));
            helper.assertTrue(after.slots().get(3).getHoverName().getString().equals(LARGE_NAME),
                    "decoded menu items still share mutable component state with the sent snapshot");
            helper.succeed();
        } finally {
            body.getInventory().clearContent();
            body.closeContainer();
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }
    }

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_spectator_network")
    public static void fragmented_hud_keeps_all_inventory_components_and_identity(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "gametest_net_hud", new BlockPos(3, 2, 3), false);
        try {
            for (int slot = 0; slot < 41; slot++) body.getInventory().setItem(slot, largePick(helper, slot));
            body.getInventory().selected = 7;
            body.setHealth(9);
            body.getFoodData().setFoodLevel(13);
            body.getFoodData().setSaturation(2.5f);
            body.experienceProgress = 0.35f;
            body.experienceLevel = 12;
            body.totalExperience = 247;
            body.resetAttackStrengthTicker();
            List<ItemStack> inventory = IntStream.range(0, 41)
                    .mapToObj(slot -> body.getInventory().getItem(slot).copy()).toList();
            SpectatorStatePayload sent = new SpectatorStatePayload(88, body.getUUID(), body.getId(),
                    helper.getLevel().dimension().location(), true, inventory, body.getInventory().selected,
                    body.getHealth(), body.getMaxHealth(), body.getFoodData().getFoodLevel(),
                    body.getFoodData().getSaturationLevel(), body.experienceProgress, body.experienceLevel,
                    body.totalExperience, body.getAttackStrengthScale(0));
            CustomPacketPayload whole = crossFragments(helper, SpectatorStatePayload.STREAM_CODEC, sent);
            helper.assertTrue(whole instanceof SpectatorStatePayload, "the registered state route did not decode HUD data");
            SpectatorStatePayload received = (SpectatorStatePayload) whole;
            helper.assertTrue(received.type().equals(SpectatorStatePayload.TYPE)
                            && received.sessionId() == sent.sessionId() && received.target().equals(sent.target())
                            && received.entityId() == sent.entityId() && received.dimension().equals(sent.dimension())
                            && received.active() && received.selected() == sent.selected()
                            && received.health() == sent.health() && received.maxHealth() == sent.maxHealth()
                            && received.food() == sent.food() && received.saturation() == sent.saturation()
                            && received.experienceProgress() == sent.experienceProgress()
                            && received.experienceLevel() == sent.experienceLevel()
                            && received.totalExperience() == sent.totalExperience()
                            && received.attackStrength() == sent.attackStrength(),
                    "fragmentation changed target identity, dimension, selected hand, or live HUD values");
            helper.assertTrue(received.inventory().size() == 41, "HUD fragmentation lost inventory or equipment slots");
            for (int slot = 0; slot < inventory.size(); slot++) {
                helper.assertTrue(ItemStack.matches(inventory.get(slot), received.inventory().get(slot)),
                        "HUD fragmentation changed the stack and components in slot " + slot);
                assertEnchanted(helper, received.inventory().get(slot));
            }
            body.getInventory().clearContent();
            sent.inventory().get(0).set(DataComponents.CUSTOM_NAME, Component.literal("sender-only"));
            helper.assertTrue(received.inventory().get(0).getHoverName().getString().equals(LARGE_NAME)
                            && received.inventory().get(0).getDamageValue() == 0
                            && received.inventory().get(40).getDamageValue() == 40,
                    "decoded HUD items alias either the live body or the sender's snapshot");
            helper.succeed();
        } finally {
            body.getInventory().clearContent();
            CompanionFactory.despawn(helper.getLevel().getServer(), body);
        }
    }

    private static ItemStack largePick(GameTestHelper helper, int damage) {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal(LARGE_NAME));
        pick.setDamageValue(damage);
        pick.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.EFFICIENCY), 3);
        return pick;
    }

    private static void assertEnchanted(GameTestHelper helper, ItemStack stack) {
        var efficiency = helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.EFFICIENCY);
        helper.assertTrue(EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(efficiency) == 3,
                "the dynamic enchantment holder did not decode against the receiving world's registries");
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload crossFragments(GameTestHelper helper,
            StreamCodec<RegistryFriendlyByteBuf, T> codec, T payload) {
        Supplier<RegistryFriendlyByteBuf> buffers = () ->
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        int bytes = Wire.size(codec, payload, buffers);
        helper.assertTrue(bytes > Wire.TO_CLIENT_PACKET && bytes < Wire.MESSAGE_BYTES,
                "the actual encoded fixture must require fragmentation and stay below 8 MiB, got " + bytes);
        List<CustomPacketPayload> packets = Fragments.packets(Wire.TO_CLIENT, codec, payload, buffers);
        helper.assertTrue(packets.size() > 1, "the large viewing payload was sent without fragments");
        Fragments.Inbox inbox = new Fragments.Inbox(Wire.TO_CLIENT);
        CustomPacketPayload complete = null;
        int chunkBytes = 0;
        for (int index = 0; index < packets.size(); index++) {
            helper.assertTrue(packets.get(index) instanceof FragmentPayload, "a large payload produced a non-fragment packet");
            FragmentPayload fragment = (FragmentPayload) packets.get(index);
            chunkBytes += fragment.chunk().length;
            var wire = Unpooled.buffer();
            try {
                FragmentPayload.TO_CLIENT_CODEC.encode(wire, fragment);
                helper.assertTrue(wire.readableBytes() <= Wire.TO_CLIENT_PACKET,
                        "an encoded fragment exceeded the client packet size");
                FragmentPayload arrived = FragmentPayload.TO_CLIENT_CODEC.decode(wire);
                helper.assertTrue(!wire.isReadable(), "the fragment codec left unread bytes");
                CustomPacketPayload assembled = NumenNetwork.assembled(inbox, arrived, helper.getLevel().registryAccess());
                if (index < packets.size() - 1) {
                    helper.assertTrue(assembled == null && inbox.assembling() == 1,
                            "the route dispatched an incomplete viewing message or discarded its fragments");
                } else {
                    helper.assertTrue(assembled != null && inbox.assembling() == 0,
                            "the registered route failed to dispatch and release the completed viewing message");
                    complete = assembled;
                }
            } finally {
                wire.release();
            }
        }
        helper.assertTrue(chunkBytes == bytes, "fragmentation lost or duplicated payload bytes");
        return complete;
    }
}
