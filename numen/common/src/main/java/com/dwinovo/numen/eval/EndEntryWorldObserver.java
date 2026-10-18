package com.dwinovo.numen.eval;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/** 同一主体、同一原生要塞入口的到达、本人开启与原生穿门链;世界事实不替代规划评分。 */
public final class EndEntryWorldObserver implements WorldEvalObservers.Observer {
    private static final int MAX_EVENTS = 128;
    private static final int MAX_PORTALS = 64;
    private static final int ARRIVAL_RADIUS = 6;
    private static final String[] KINDS = {"portal_reached", "eye_inserted", "other_eye_inserted", "portal_activated",
            "native_end_entry", "dimension_change", "hurt", "inventory_change", "sampled_chunk_change",
            "danger_entered", "danger_cleared", "observation_gap", "death"};
    private final EndResourcesWorldObserver resources = new EndResourcesWorldObserver();
    private final Map<String, PortalState> portals = new LinkedHashMap<>();
    private final JsonArray events = new JsonArray();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, JsonObject> first = new LinkedHashMap<>();
    private NumenPlayer subject;
    private UUID subjectUuid;
    private JsonObject initialSample;
    private Map<String, Integer> inventoryBefore;
    private String chunkBefore;
    private boolean safeBefore;
    private long lastTick = -1;
    private int heldTicks;
    private int gaps;
    private int eventsTotal;
    private boolean dead;
    private boolean portalEvidenceTruncated;
    private boolean nativeEpisode;
    private PortalState entered;

    @Override public void started(NumenPlayer body) {
        subject = body;
        subjectUuid = body.getUUID();
        sampleChanges(body);
        lastTick = now(body);
    }

    @Override public void tick(NumenPlayer body) {
        subject = body;
        long tick = now(body);
        if (tick == lastTick) return;
        if (lastTick >= 0 && tick != lastTick + 1) unavailable(tick);
        sampleChanges(body);
        if (!body.isAlive()) latchDeath(body, "server_tick_dead_body");
        if (body.level().dimension() == Level.OVERWORLD) scanNearbyPortals(body);
        boolean valid = nativeEpisode && entered != null && completeChain(entered) && body.isAlive() && !dead
                && gaps == 0 && !portalEvidenceTruncated && body.level().dimension() == Level.END;
        heldTicks = valid ? (tick == lastTick + 1 ? heldTicks + 1 : 1) : 0;
        lastTick = tick;
    }

    @Override public void unavailable(long tick) {
        gaps++;
        heldTicks = 0;
        JsonObject event = new JsonObject();
        event.addProperty("subject_uuid", subjectUuid.toString());
        event.addProperty("game_tick", tick);
        event.addProperty("reason", "server subject missing or server-tick observation discontinuity");
        record("observation_gap", event);
    }

    @Override public void itemUseOnStarted(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item) {
        if (!sameServer(level) || !item.is(Items.ENDER_EYE) || !previous.is(Blocks.END_PORTAL_FRAME)) return;
        PortalState portal = findPortal(level, pos);
        if (portal != null && actor.getUUID().equals(subjectUuid)) arrival(portal, subject);
    }

    @Override public void itemUsedOn(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item, int countAfter) {
        if (!sameServer(level) || !item.is(Items.ENDER_EYE) || !previous.is(Blocks.END_PORTAL_FRAME)
                || previous.getValue(EndPortalFrameBlock.HAS_EYE)
                || item.getCount() - countAfter != 1) return;
        BlockState after = level.getBlockState(pos);
        if (!after.is(Blocks.END_PORTAL_FRAME) || !after.getValue(EndPortalFrameBlock.HAS_EYE)) return;
        PortalState portal = findPortal(level, pos);
        if (portal == null) return;
        boolean own = actor.getUUID().equals(subjectUuid);
        if (own) portal.ownEyes++; else portal.otherEyes++;
        JsonObject event = sample(subject);
        event.addProperty("actor_uuid", actor.getUUID().toString());
        event.addProperty("portal_id", portal.id);
        event.add("frame", blockCoordinates(pos));
        event.addProperty("native_eye_inserted", true);
        event.addProperty("eye_inventory_delta", -1);
        event.add("portal", snapshot(portal));
        record(own ? "eye_inserted" : "other_eye_inserted", event);
        JsonObject facts = snapshot(portal);
        if (own && portal.arrival != null && portal.activation == null && !portal.initialActive
                && portal.otherEyes == 0 && facts.get("active").getAsBoolean()) {
            JsonObject activation = event.deepCopy();
            activation.addProperty("kind", "portal_activated");
            activation.addProperty("evidence_source", "native_ender_eye_use_on_committed");
            portal.activation = activation;
            record("portal_activated", activation);
        }
    }

    @Override public void portalTravelled(NumenPlayer body, ServerLevel source, BlockPos entry, Entity arrived) {
        if (source.dimension() != Level.OVERWORLD || !(arrived instanceof NumenPlayer current) || !arrived.getUUID().equals(subjectUuid)
                || arrived.level().dimension() != Level.END || !source.getBlockState(entry).is(Blocks.END_PORTAL)) return;
        subject = current;
        PortalState portal = findPortal(source, entry);
        if (portal == null) return;
        JsonObject event = sample(current);
        event.addProperty("same_entity_instance", body == arrived);
        event.addProperty("destination_uuid", arrived.getUUID().toString());
        event.addProperty("portal_id", portal.id);
        event.add("portal", snapshot(portal));
        event.add("source_block", blockCoordinates(entry));
        event.addProperty("from_dimension", source.dimension().location().toString());
        event.addProperty("to_dimension", arrived.level().dimension().location().toString());
        event.addProperty("evidence_source", "vanilla_handle_portal_change_dimension");
        event.addProperty("kind", "native_end_entry");
        portal.entry = event;
        entered = portal;
        nativeEpisode = true;
        heldTicks = 0;
        record("native_end_entry", event);
    }

    @Override public void dimensionChanged(NumenPlayer body) {
        subject = body;
        nativeEpisode = false;
        heldTicks = 0;
        JsonObject event = sample(body);
        event.addProperty("to_dimension", body.level().dimension().location().toString());
        event.addProperty("evidence_source", "native_dimension_change_hook");
        record("dimension_change", event);
    }

    @Override public void hurt(NumenPlayer body, net.minecraft.world.damagesource.DamageSource source, float amount) {
        JsonObject event = sample(body);
        event.addProperty("damage_type_message_id", source.type().msgId());
        event.addProperty("reported_amount_before_mitigation", amount);
        event.addProperty("health_after", body.getHealth());
        event.addProperty("evidence_source", "native_accepted_damage_event");
        record("hurt", event);
    }

    @Override public void died(NumenPlayer body) {
        latchDeath(body, "native_companion_death_before_recovery");
    }

    private void latchDeath(NumenPlayer body, String source) {
        if (dead) return;
        subject = body;
        dead = true;
        nativeEpisode = false;
        heldTicks = 0;
        JsonObject event = sample(body);
        event.addProperty("health_at_death", body.getHealth());
        event.addProperty("evidence_source", source);
        record("death", event);
    }

    @Override public JsonObject observe(NumenPlayer body) {
        subject = body;
        JsonObject facts = resources.scan(body).facts();
        JsonObject evidence = facts.getAsJsonObject("evidence");
        PortalState selected = selectedPortal();
        boolean generated = selected != null && selected.generated;
        boolean chain = selected != null && completeChain(selected);
        facts.addProperty("subject_uuid", subjectUuid.toString());
        facts.addProperty("portal_reached", generated && selected.arrival != null);
        facts.addProperty("portal_activated_by_companion", generated && ownActivation(selected));
        facts.addProperty("native_end_entry", nativeEpisode && generated && chain && gaps == 0 && !portalEvidenceTruncated && !dead);
        facts.addProperty("end_alive_held_ticks", heldTicks);
        facts.addProperty("observation_complete", gaps == 0 && !portalEvidenceTruncated);
        JsonObject entry = new JsonObject();
        entry.addProperty("subject_uuid", subjectUuid.toString());
        entry.add("portal", selected == null ? JsonNull.INSTANCE : snapshot(selected));
        entry.add("arrival", selected == null || selected.arrival == null ? JsonNull.INSTANCE : selected.arrival.deepCopy());
        entry.add("activation", selected == null || selected.activation == null ? JsonNull.INSTANCE : selected.activation.deepCopy());
        entry.add("entry", selected == null || selected.entry == null ? JsonNull.INSTANCE : selected.entry.deepCopy());
        entry.addProperty("calibration_native_end_entry", chain && !generated);
        entry.addProperty("native_entry_episode_active", nativeEpisode);
        entry.addProperty("critical_evidence_complete", gaps == 0 && !portalEvidenceTruncated);
        entry.addProperty("observation_gaps", gaps);
        entry.addProperty("first_death_observed", dead);
        entry.add("last_verified_game_tick", lastTick < 0 ? JsonNull.INSTANCE : new JsonPrimitive(lastTick));
        evidence.add("end_entry", entry);
        evidence.add("initial_sample", initialSample.deepCopy());
        JsonObject consumption = new JsonObject();
        consumption.addProperty("initial_ender_eyes", initialSample.getAsJsonObject("inventory").has("minecraft:ender_eye")
                ? initialSample.getAsJsonObject("inventory").get("minecraft:ender_eye").getAsInt() : 0);
        consumption.addProperty("current_ender_eyes", facts.get("ender_eyes").getAsInt());
        consumption.addProperty("native_frame_insertions", counts.getOrDefault("eye_inserted", 0));
        consumption.addProperty("inventory_delta_is_sampled", true);
        consumption.addProperty("final_resource_threshold", 0);
        evidence.add("eye_consumption", consumption);
        evidence.add("events", events.deepCopy());
        evidence.addProperty("events_total", eventsTotal);
        evidence.addProperty("events_truncated", eventsTotal > MAX_EVENTS);
        evidence.addProperty("portal_evidence_truncated", portalEvidenceTruncated);
        JsonObject coverage = new JsonObject();
        for (String kind : KINDS) {
            JsonObject item = new JsonObject();
            int count = counts.getOrDefault(kind, 0);
            item.addProperty("status", count > 0 ? "observed" : "not_covered");
            item.addProperty("sampled_events", count);
            item.add("first_event", first.containsKey(kind) ? first.get(kind).deepCopy() : JsonNull.INSTANCE);
            coverage.add(kind, item);
        }
        for (String kind : new String[]{"risk_replanning", "resource_site_replanning", "remember_unfinished_goal"}) {
            JsonObject item = new JsonObject();
            item.addProperty("status", "not_measured");
            item.addProperty("reason", "world facts do not attribute memory or planning; review the tool trace");
            coverage.add(kind, item);
        }
        evidence.add("coverage", coverage);
        return facts;
    }

    private void sampleChanges(NumenPlayer body) {
        var loadout = resources.scan(body);
        Map<String, Integer> inventory = loadout.carried();
        JsonObject sample = sample(body);
        boolean safe = loadout.facts().get("safe_state").getAsBoolean();
        String chunk = body.level().dimension().location() + ":" + (body.blockPosition().getX() >> 4) + ":" + (body.blockPosition().getZ() >> 4);
        if (inventoryBefore == null) {
            initialSample = sample.deepCopy();
            initialSample.add("inventory", inventoryCounts(inventory));
            initialSample.add("resources", loadout.facts().getAsJsonObject("evidence").get("resources").deepCopy());
        } else {
            if (!inventory.equals(inventoryBefore)) {
                JsonObject event = sample.deepCopy();
                event.add("before", inventoryCounts(inventoryBefore));
                event.add("after", inventoryCounts(inventory));
                record("inventory_change", event);
            }
            if (!chunk.equals(chunkBefore)) {
                JsonObject event = sample.deepCopy();
                event.addProperty("from_chunk", chunkBefore);
                record("sampled_chunk_change", event);
            }
            if (safe != safeBefore) record(safe ? "danger_cleared" : "danger_entered", sample);
        }
        inventoryBefore = inventory;
        chunkBefore = chunk;
        safeBefore = safe;
    }

    private void scanNearbyPortals(NumenPlayer body) {
        BlockPos origin = body.blockPosition();
        ServerLevel level = body.serverLevel();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-ARRIVAL_RADIUS, -3, -ARRIVAL_RADIUS),
                origin.offset(ARRIVAL_RADIUS, 3, ARRIVAL_RADIUS))) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) continue;
            PortalState portal = findPortal(level, pos);
            if (portal != null) arrival(portal, body);
        }
    }

    private void arrival(PortalState portal, NumenPlayer body) {
        if (portal.arrival != null || body.level().dimension() != portal.level.dimension()
                || body.position().distanceToSqr(portal.center.getCenter()) > ARRIVAL_RADIUS * ARRIVAL_RADIUS) return;
        JsonObject event = sample(body);
        event.addProperty("kind", "portal_reached");
        event.addProperty("portal_id", portal.id);
        event.add("portal", snapshot(portal));
        event.addProperty("evidence_source", "server_body_near_complete_frame_ring");
        portal.arrival = event;
        record("portal_reached", event);
    }

    private PortalState findPortal(ServerLevel level, BlockPos member) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos center = member.offset(dx, 0, dz);
            if (!ring(level, center)) continue;
            String id = level.dimension().location() + ":" + center.getX() + ":" + center.getY() + ":" + center.getZ();
            PortalState known = portals.get(id);
            if (known != null) return known;
            if (portals.size() >= MAX_PORTALS) {
                portalEvidenceTruncated = true;
                return null;
            }
            StructureStart start = level.structureManager().getStructureWithPieceAt(center, holder -> holder.is(BuiltinStructures.STRONGHOLD));
            boolean generated = start.isValid() && start.getPieces().stream().anyMatch(piece ->
                    piece instanceof StrongholdPieces.PortalRoom && piece.getBoundingBox().isInside(center));
            PortalState portal = new PortalState(level, center.immutable(), id, generated);
            JsonObject baseline = snapshot(portal);
            portal.initialEyes = baseline.get("eyes").getAsInt();
            portal.initialActive = baseline.get("active").getAsBoolean();
            portals.put(id, portal);
            return portal;
        }
        return null;
    }

    private static boolean ring(ServerLevel level, BlockPos center) {
        for (int offset = -1; offset <= 1; offset++) {
            if (!frame(level, center.offset(offset, 0, -2), Direction.SOUTH)
                    || !frame(level, center.offset(offset, 0, 2), Direction.NORTH)
                    || !frame(level, center.offset(-2, 0, offset), Direction.EAST)
                    || !frame(level, center.offset(2, 0, offset), Direction.WEST)) return false;
        }
        return true;
    }

    private static boolean frame(ServerLevel level, BlockPos pos, Direction facing) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.FACING) == facing;
    }

    private JsonObject snapshot(PortalState portal) {
        JsonObject result = blockCoordinates(portal.center);
        result.addProperty("portal_id", portal.id);
        result.addProperty("dimension", portal.level.dimension().location().toString());
        result.addProperty("generated_stronghold", portal.generated);
        result.addProperty("portal_origin", portal.generated ? "generated_stronghold" : "calibration_fixture");
        result.addProperty("initial_eyes", portal.initialEyes);
        result.addProperty("initial_active", portal.initialActive);
        result.addProperty("companion_eye_insertions", portal.ownEyes);
        result.addProperty("other_eye_insertions", portal.otherEyes);
        JsonArray frames = new JsonArray();
        int eyes = 0;
        for (int offset = -1; offset <= 1; offset++) {
            for (BlockPos pos : new BlockPos[]{portal.center.offset(offset, 0, -2), portal.center.offset(offset, 0, 2),
                    portal.center.offset(-2, 0, offset), portal.center.offset(2, 0, offset)}) {
                BlockState state = portal.level.getBlockState(pos);
                JsonObject item = blockCoordinates(pos);
                item.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                boolean hasEye = state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE);
                item.addProperty("has_eye", hasEye);
                item.addProperty("facing", state.is(Blocks.END_PORTAL_FRAME) ? state.getValue(EndPortalFrameBlock.FACING).getName() : "missing");
                if (hasEye) eyes++;
                frames.add(item);
            }
        }
        JsonArray blocks = new JsonArray();
        boolean active = ring(portal.level, portal.center) && eyes == 12;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos pos = portal.center.offset(dx, 0, dz);
            BlockState state = portal.level.getBlockState(pos);
            JsonObject item = blockCoordinates(pos);
            item.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            blocks.add(item);
            active &= state.is(Blocks.END_PORTAL);
        }
        result.add("frames", frames);
        result.add("portal_blocks", blocks);
        result.addProperty("eyes", eyes);
        result.addProperty("active", active);
        return result;
    }

    private PortalState selectedPortal() {
        if (entered != null) return entered;
        PortalState selected = null;
        for (PortalState portal : portals.values()) {
            if (selected == null || portal.activation != null || selected.arrival == null && portal.arrival != null) selected = portal;
        }
        return selected;
    }

    private boolean ownActivation(PortalState portal) {
        return portal != null && portal.activation != null && portal.ownEyes > 0 && portal.otherEyes == 0 && !portal.initialActive;
    }

    private boolean completeChain(PortalState portal) {
        return portal.arrival != null && ownActivation(portal) && portal.entry != null
                && portal.entry.getAsJsonObject("portal").get("active").getAsBoolean();
    }

    private boolean sameServer(ServerLevel level) {
        return subject != null && subject.serverLevel().getServer() == level.getServer() && level.dimension() == Level.OVERWORLD;
    }

    private void record(String kind, JsonObject event) {
        JsonObject copy = event.deepCopy();
        copy.addProperty("kind", kind);
        counts.merge(kind, 1, Integer::sum);
        first.putIfAbsent(kind, copy.deepCopy());
        eventsTotal++;
        if (events.size() < MAX_EVENTS) events.add(copy);
    }

    private static long now(NumenPlayer body) { return body.serverLevel().getServer().overworld().getGameTime(); }
    // 门证据沿用position数组,并显式提供判分协议所需的整数方块坐标。
    private static JsonObject blockCoordinates(BlockPos pos) {
        JsonObject result = SurvivalWorldObserver.coordinates(pos);
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        return result;
    }
    private JsonObject sample(NumenPlayer body) {
        JsonObject result = SurvivalWorldObserver.coordinates(body.blockPosition());
        result.addProperty("x", body.getX());
        result.addProperty("y", body.getY());
        result.addProperty("z", body.getZ());
        result.addProperty("game_tick", now(body));
        result.addProperty("dimension", body.level().dimension().location().toString());
        result.addProperty("subject_uuid", body.getUUID().toString());
        return result;
    }
    private static JsonObject inventoryCounts(Map<String, Integer> inventory) {
        JsonObject result = new JsonObject();
        inventory.forEach(result::addProperty);
        return result;
    }
    private static final class PortalState {
        final ServerLevel level;
        final BlockPos center;
        final String id;
        final boolean generated;
        int initialEyes;
        boolean initialActive;
        int ownEyes;
        int otherEyes;
        JsonObject arrival;
        JsonObject activation;
        JsonObject entry;
        PortalState(ServerLevel level, BlockPos center, String id, boolean generated) {
            this.level = level;
            this.center = center;
            this.id = id;
            this.generated = generated;
        }
    }
}
