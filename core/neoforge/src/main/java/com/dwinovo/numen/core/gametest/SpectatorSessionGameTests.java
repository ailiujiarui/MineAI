package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.FakeConnection;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.SpectatorRequestPayload;
import com.dwinovo.numen.network.payload.SpectatorStatePayload;
import com.dwinovo.numen.spectator.OwnerLocation;
import com.dwinovo.numen.spectator.ServerSpectatorSessions;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 用真实玩家包、身体导航与死亡恢复钉住观看会话的服务端边界。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpectatorSessionGameTests {
    @GameTest(template = "floor16", timeoutTicks = 160)
    public static void spectator_switch_keeps_original_location_and_restores(GameTestHelper helper) {
        NumenPlayer first = spawnAt(helper, "spec_switch_first", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, first, "spec_switch_owner");
        NumenPlayer second = spawnAt(helper, "spec_switch_second", new BlockPos(4, 2, 4), false);
        second.setOwnerUuid(owner.getUUID());
        NumenPlayer stranger = spawnAt(helper, "spec_switch_stranger", new BlockPos(5, 2, 5), false);
        steps(helper).thenExecuteAfter(20, () -> {
            Vec3 original = at(helper, new BlockPos(13, 2, 13));
            owner.teleportTo(helper.getLevel(), original.x, original.y, original.z, Set.of(), 72, -18);
            owner.setGameMode(GameType.CREATIVE);
            owner.getAbilities().flying = true;
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            ServerSpectatorSessions.enter(stranger, first.getUUID());
            helper.assertTrue(!ServerSpectatorSessions.isViewing(stranger), "stranger can watch another owner's NPC");
            ServerSpectatorSessions.enter(owner, first.getUUID());
            long firstSession = ServerSpectatorSessions.sessionFor(first).id();
            helper.assertTrue(owner.getCamera() == first, "initial camera did not bind");
            ServerSpectatorSessions.enter(owner, second.getUUID());
            helper.assertTrue(ServerSpectatorSessions.sessionFor(second).id() > firstSession,
                    "target switch did not invalidate old packets");
            helper.assertTrue(OwnerLocation.of(owner).position().equals(original), "switch overwrote original owner position");
            ServerSpectatorSessions.request(owner, first.getUUID(), SpectatorRequestPayload.EXIT);
            helper.assertTrue(ServerSpectatorSessions.isViewing(owner), "old target exit stopped new target");
            owner.setHealth(9);
            var status = dataIn(lua(second, "numen.status.owner()").reply());
            helper.assertTrue(status.get("hp").getAsFloat() == 9, "owner health froze at session start");
            helper.assertTrue(Math.abs(status.getAsJsonObject("pos").get("x").getAsDouble() - original.x) < 0.001,
                    "perception reports camera coordinates instead of saved owner position");
            long beforeRemote = ServerSpectatorSessions.sessionFor(second).id();
            second.teleportTo(second.getX() + 1000, second.getY(), second.getZ());
            ServerSpectatorSessions.tick(helper.getLevel().getServer());
            helper.assertTrue(ServerSpectatorSessions.sessionFor(second).id() > beforeRemote,
                    "remote same-body teleport did not invalidate old display state");
            SpectatorRequestPayload.handle(new SpectatorRequestPayload(second.getUUID(), SpectatorRequestPayload.EXIT,
                    beforeRemote, 0), owner);
            helper.assertTrue(ServerSpectatorSessions.isViewing(owner), "old-generation exit stopped a rebound target");
            helper.assertTrue(owner.position().distanceToSqr(second.position()) < 0.01, "remote target did not move viewer for chunk streaming");
            helper.assertTrue(OwnerLocation.of(owner).position().equals(original), "remote move overwrote original position");
            ServerSpectatorSessions.exit(owner);
            helper.assertTrue(owner.getCamera() == owner && owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                    "exit failed to restore own camera and original mode");
            helper.assertTrue(owner.position().distanceToSqr(original) < 0.001, "exit failed to restore original position");
            helper.assertTrue(owner.getYRot() == 72 && owner.getXRot() == -18 && owner.getAbilities().flying,
                    "exit failed to restore orientation or flying state");
            helper.assertTrue(owner.getInventory().getItem(0).is(Items.DIAMOND)
                    && owner.getInventory().getItem(0).getCount() == 7, "watching modified owner's inventory");
            owner.teleportTo(original.x + 2, original.y, original.z);
            helper.assertTrue(OwnerLocation.of(owner).position().equals(owner.position()), "exit did not clear saved location source");
            CompanionFactory.despawn(owner.getServer(), first);
            CompanionFactory.despawn(owner.getServer(), second);
            CompanionFactory.despawn(owner.getServer(), stranger);
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 120)
    public static void spectator_packets_cannot_modify_owner_or_container(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_input_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, body, "spec_input_owner");
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 12));
        steps(helper).thenExecuteAfter(20, () -> {
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            owner.getInventory().selected = 0;
            body.getInventory().setItem(0, new ItemStack(Items.EMERALD, 9));
            body.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                    ChestMenu.threeRows(id, inventory, chest), Component.literal("Read-only chest")));
            ServerSpectatorSessions.enter(owner, body.getUUID());
            for (ClickType type : ClickType.values()) {
                owner.connection.handleContainerClick(new ServerboundContainerClickPacket(
                        owner.containerMenu.containerId, owner.containerMenu.getStateId(), 36, 1, type,
                        new ItemStack(Items.DIRT, 64), new Int2ObjectOpenHashMap<>()));
                owner.connection.handleContainerClick(new ServerboundContainerClickPacket(
                        body.containerMenu.containerId, body.containerMenu.getStateId(), 0, 1, type,
                        ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
            }
            owner.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(3));
            for (var action : new ServerboundPlayerActionPacket.Action[]{
                    ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
                    ServerboundPlayerActionPacket.Action.DROP_ITEM,
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND}) {
                owner.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
            }
            helper.assertTrue(owner.getInventory().selected == 0, "number key changed the owner's selected slot");
            helper.assertTrue(owner.getInventory().getItem(0).is(Items.DIAMOND)
                    && owner.getInventory().getItem(0).getCount() == 5, "click/drop changed owner inventory");
            helper.assertTrue(body.getInventory().getItem(0).is(Items.EMERALD)
                    && body.getInventory().getItem(0).getCount() == 9, "watcher changed NPC inventory");
            helper.assertTrue(chest.getItem(0).getCount() == 12 && body.containerMenu.getCarried().isEmpty(),
                    "watcher changed shared container or cursor");
            ServerSpectatorSessions.exit(owner);
            body.closeContainer();
            CompanionFactory.despawn(owner.getServer(), body);
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 1000)
    public static void spectator_follow_navigates_to_original_owner_position(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_follow_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, body, "spec_follow_owner");
        Vec3 original = at(helper, new BlockPos(13, 2, 13));
        Vec3 start = body.position();
        ToolRun[] follow = new ToolRun[1];
        steps(helper).thenExecuteAfter(20, () -> {
            owner.teleportTo(original.x, original.y, original.z);
            owner.setOnGround(true);
            ServerSpectatorSessions.enter(owner, body.getUUID());
            follow[0] = lua(body, "numen.move.follow()");
            helper.assertTrue(owner.position().distanceToSqr(body.position()) < 0.01,
                    "viewer fixture is not at camera target");
        }).thenWaitUntil(() -> {
            helper.assertTrue(follow[0].task() != null && !follow[0].done(), "follow was not left active");
            helper.assertTrue(body.position().distanceToSqr(start) > 1,
                    "follow slept because it used camera position");
            helper.assertTrue(body.position().distanceTo(original) <= 4.5,
                    "NPC did not navigate to original owner position");
        }).thenExecute(() -> {
            ServerSpectatorSessions.exit(owner);
            CompanionFactory.despawn(owner.getServer(), body);
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 360)
    public static void spectator_cross_dimension_death_rebind_uses_original_spawn(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_respawn_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, body, "spec_respawn_owner");
        Vec3 original = at(helper, new BlockPos(13, 2, 13));
        long[] binding = {0};
        steps(helper).thenExecuteAfter(20, () -> {
            owner.teleportTo(original.x, original.y, original.z);
            owner.setOnGround(true);
            var registry = CompanionRegistry.get(owner.getServer());
            registry.put(body.getUUID(), new CompanionRegistry.Entry(body.getName().getString(), owner.getUUID(),
                    helper.getLevel().dimension(), body.blockPosition()));
            ServerSpectatorSessions.enter(owner, body.getUUID());
            binding[0] = ServerSpectatorSessions.sessionFor(body).id();
            var nether = owner.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "Nether missing from GameTest server");
            body.setNoGravity(true);
            body.teleportTo(nether, original.x, 120, original.z, Set.of(), 0, 0);
            ServerSpectatorSessions.tick(owner.getServer());
            helper.assertTrue(owner.level() == nether && owner.getCamera() == body, "cross-dimension camera did not rebind");
            helper.assertTrue(ServerSpectatorSessions.sessionFor(body).id() > binding[0], "dimension rebind did not invalidate old state");
            helper.assertTrue(OwnerLocation.of(owner).level() == helper.getLevel()
                    && OwnerLocation.of(owner).position().equals(original), "dimension rebind overwrote original owner location");
            var status = dataIn(lua(body, "numen.status.owner()").reply());
            helper.assertTrue(!status.get("sameDimension").getAsBoolean()
                    && status.get("dimension").getAsString().equals("minecraft:overworld"),
                    "owner dimension query follows camera instead of original stand");
            ToolRun namedFollow = lua(body, "numen.move.follow(" + owner.getId() + ")");
            helper.assertTrue(namedFollow.task() == null && namedFollow.done() && !namedFollow.succeeded(),
                    "explicit owner follow ignored its original cross-dimension rejection rule");
            ToolRun follow = lua(body, "numen.move.follow()");
            helper.assertTrue(follow.task() != null, "cross-dimension follow was not accepted");
        }).thenExecuteAfter(10, () -> {
            helper.assertTrue(body.level().dimension().equals(Level.NETHER),
                    "watching changed the existing cross-dimension follow rule by teleporting the NPC");
            body.setHealth(0);
            helper.assertTrue(CompanionRegistry.get(owner.getServer()).find(body.getUUID()).diedAt() == 0L,
                    "death fixture already ran the entity death tick");
            ServerSpectatorSessions.tick(owner.getServer());
            helper.assertTrue(ServerSpectatorSessions.isViewing(owner)
                    && OwnerLocation.of(owner).position().equals(original),
                    "zero-health transition before death registration discarded the viewing session");
        }).thenWaitUntil(() -> {
            NumenPlayer revived = NumenPlayer.findByUuid(owner.getServer(), body.getUUID());
            helper.assertTrue(revived != null && revived != body, "NPC has not respawned with a new body");
            helper.assertTrue(revived.level() == helper.getLevel() && revived.position().distanceTo(original) < 5,
                    "NPC respawn used " + revived.level().dimension().location() + " " + revived.position()
                            + " instead of original owner location " + original);
            helper.assertTrue(owner.getCamera() == revived && ServerSpectatorSessions.sessionFor(revived) != null,
                    "watch session did not bind replacement entity: active=" + ServerSpectatorSessions.isViewing(owner)
                            + ", camera=" + owner.getCamera().getUUID() + ", replacement=" + revived.getUUID());
            helper.assertTrue(OwnerLocation.of(owner).position().equals(original), "respawn rebind overwrote owner anchor");
        }).thenExecute(() -> {
            NumenPlayer revived = NumenPlayer.findByUuid(owner.getServer(), body.getUUID());
            ServerSpectatorSessions.exit(owner);
            helper.assertTrue(owner.level() == helper.getLevel() && owner.position().distanceToSqr(original) < 0.001,
                    "cross-dimension exit failed to restore original stand");
            CompanionFactory.despawn(owner.getServer(), revived);
            CompanionRegistry.get(owner.getServer()).remove(body.getUUID());
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 1000)
    public static void spectator_named_owner_follow_uses_original_position(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_named_follow_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, body, "spec_named_follow_owner");
        NumenPlayer watched = spawnAt(helper, "spec_named_follow_watched", new BlockPos(4, 2, 4), false);
        watched.setOwnerUuid(owner.getUUID());
        Vec3 original = at(helper, new BlockPos(13, 2, 13));
        Vec3 start = body.position();
        ToolRun[] follow = new ToolRun[1];
        steps(helper).thenExecuteAfter(20, () -> {
            owner.teleportTo(original.x, original.y, original.z);
            owner.setOnGround(true);
            var nether = owner.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "Nether missing from GameTest server");
            watched.setNoGravity(true);
            watched.teleportTo(nether, original.x, 120, original.z, Set.of(), 0, 0);
            ServerSpectatorSessions.enter(owner, watched.getUUID());
            helper.assertTrue(owner.level() != body.level() && OwnerLocation.of(owner).level() == body.level(),
                    "named-owner fixture did not separate camera dimension from original stand");
            var status = dataIn(lua(body, "numen.status.owner()").reply());
            helper.assertTrue(status.get("sameDimension").getAsBoolean()
                    && Math.abs(status.get("distance").getAsDouble()
                            - Math.round(body.position().distanceTo(original) * 10.0) / 10.0) < 0.001
                    && Math.abs(status.getAsJsonObject("pos").get("x").getAsDouble() - original.x) < 0.001
                    && Math.abs(status.getAsJsonObject("pos").get("y").getAsDouble() - original.y) < 0.001
                    && Math.abs(status.getAsJsonObject("pos").get("z").getAsDouble() - original.z) < 0.001,
                    "owner lookup did not use original dimension and distance while watching another dimension");
            follow[0] = lua(body, "numen.move.follow(" + owner.getId() + ")");
        }).thenWaitUntil(() -> {
            helper.assertTrue(follow[0].task() != null && !follow[0].done(), "explicit owner follow was not left active");
            helper.assertTrue(body.position().distanceToSqr(start) > 1,
                    "explicit owner follow slept because it used camera position");
            helper.assertTrue(body.position().distanceTo(original) <= 4.5,
                    "explicit owner follow did not navigate to original owner stand");
        }).thenExecute(() -> {
            ServerSpectatorSessions.exit(owner);
            CompanionFactory.despawn(owner.getServer(), body);
            CompanionFactory.despawn(owner.getServer(), watched);
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 120)
    public static void spectator_logout_restores_before_player_save(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_logout_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentPlayer(helper, body, "spec_logout_owner");
        steps(helper).thenExecuteAfter(20, () -> {
            Vec3 original = at(helper, new BlockPos(13, 2, 13));
            owner.teleportTo(helper.getLevel(), original.x, original.y, original.z, Set.of(), 23, 11);
            owner.setGameMode(GameType.CREATIVE);
            ServerSpectatorSessions.enter(owner, body.getUUID());
            leave(owner);
            helper.assertTrue(!ServerSpectatorSessions.isViewing(owner), "logout leaked viewing session");
            helper.assertTrue(owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE
                    && owner.position().distanceToSqr(original) < 0.001 && owner.getCamera() == owner,
                    "logout removed owner before restoring position/mode/camera");
            CompanionFactory.despawn(body.getServer(), body);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 120)
    public static void spectator_restores_preexisting_camera_and_exact_stand(GameTestHelper helper) {
        NumenPlayer first = spawnAt(helper, "spec_camera_first", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, first, "spec_camera_owner");
        NumenPlayer second = spawnAt(helper, "spec_camera_second", new BlockPos(12, 2, 12), false);
        second.setOwnerUuid(owner.getUUID());
        List<SpectatorStatePayload> states = new ArrayList<>();
        steps(helper).thenExecuteAfter(20, () -> {
            owner.connection = new ServerGamePacketListenerImpl(owner.getServer(), new FakeConnection(), owner,
                    CommonListenerCookie.createInitial(owner.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket custom
                            && custom.payload() instanceof SpectatorStatePayload state) states.add(state);
                    super.send(packet);
                }
            };
            owner.setGameMode(GameType.SPECTATOR);
            owner.setCamera(first);
            Vec3 original = owner.position();
            owner.setYRot(31);
            owner.setXRot(-9);
            ServerSpectatorSessions.enter(owner, second.getUUID());
            ServerSpectatorSessions.exit(owner);
            helper.assertTrue(owner.getCamera() == first && owner.gameMode.getGameModeForPlayer() == GameType.SPECTATOR,
                    "preexisting camera or original spectator mode was lost");
            helper.assertTrue(owner.position().distanceToSqr(original) < 0.001
                    && owner.getYRot() == 31 && owner.getXRot() == -9,
                    "camera restoration changed original position/orientation");
            SpectatorStatePayload exited = states.getLast();
            helper.assertTrue(!exited.active() && exited.entityId() == first.getId()
                    && exited.target().equals(second.getUUID())
                    && exited.dimension().equals(helper.getLevel().dimension().location()),
                    "exit state did not carry the authoritative restored camera entity id");
            owner.setCamera(owner);
            CompanionFactory.despawn(owner.getServer(), first);
            CompanionFactory.despawn(owner.getServer(), second);
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 120)
    public static void spectator_target_leaves_restores_owner(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_leaving_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentPlayer(helper, body, "spec_leaving_owner");
        steps(helper).thenExecuteAfter(20, () -> {
            Vec3 original = at(helper, new BlockPos(13, 2, 13));
            owner.teleportTo(helper.getLevel(), original.x, original.y, original.z, Set.of(), 19, -7);
            owner.setGameMode(GameType.CREATIVE);
            ServerSpectatorSessions.enter(owner, body.getUUID());
            CompanionFactory.despawn(owner.getServer(), body);
            ServerSpectatorSessions.tick(owner.getServer());
            helper.assertTrue(!ServerSpectatorSessions.isViewing(owner), "departed target kept its viewing session");
            helper.assertTrue(owner.getCamera() == owner && owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE
                    && owner.position().distanceToSqr(original) < 0.001 && owner.getYRot() == 19 && owner.getXRot() == -7,
                    "target departure failed to restore owner's original camera/mode/position/orientation");
            helper.assertTrue(OwnerLocation.of(owner).position().equals(owner.position()),
                    "target departure kept the saved owner-position source");
            leave(owner);
        }).thenSucceed();
    }

    @GameTest(template = "floor16", timeoutTicks = 120)
    public static void spectator_held_maps_copy_pixels_without_registering_viewer(GameTestHelper helper) {
        NumenPlayer body = spawnAt(helper, "spec_map_body", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, body, "spec_map_owner");
        List<ClientboundMapItemDataPacket> maps = new ArrayList<>();
        steps(helper).thenExecuteAfter(20, () -> {
            owner.connection = new ServerGamePacketListenerImpl(owner.getServer(), new FakeConnection(), owner,
                    CommonListenerCookie.createInitial(owner.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundMapItemDataPacket map) maps.add(map);
                    super.send(packet);
                }
            };
            ItemStack map = MapItem.create(body.level(), body.blockPosition().getX(), body.blockPosition().getZ(),
                    (byte) 0, true, false);
            var data = MapItem.getSavedData(map, body.level());
            data.setColor(0, 0, (byte) 7);
            body.getInventory().selected = 0;
            body.getInventory().setItem(0, map);
            body.getInventory().setItem(40, map.copy());
            ServerSpectatorSessions.enter(owner, body.getUUID());
            helper.assertTrue(maps.size() == 1 && maps.getFirst().colorPatch().orElseThrow().mapColors()[0] == 7,
                    "first held-map pixels were absent or main/off duplicate was sent twice");
            helper.assertTrue(data.getUpdatePacket(map.get(DataComponents.MAP_ID), owner) == null,
                    "viewing registered the owner as a real map carrier");
            ServerSpectatorSessions.tick(owner.getServer());
            helper.assertTrue(maps.size() == 1, "unchanged map pixels were resent");
            data.setColor(0, 0, (byte) 12);
            ServerSpectatorSessions.tick(owner.getServer());
            helper.assertTrue(maps.size() == 2 && maps.getLast().colorPatch().orElseThrow().mapColors()[0] == 12,
                    "changed map pixels did not reach watcher");
            helper.assertTrue(maps.getFirst().colorPatch().orElseThrow().mapColors()[0] == 7,
                    "old map packet aliases mutable world colors");
            ServerSpectatorSessions.exit(owner);
            CompanionFactory.despawn(owner.getServer(), body);
            leave(owner);
        }).thenSucceed();
    }

    private static Vec3 at(GameTestHelper helper, BlockPos relative) {
        BlockPos absolute = helper.absolutePos(relative);
        return new Vec3(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5);
    }
}
