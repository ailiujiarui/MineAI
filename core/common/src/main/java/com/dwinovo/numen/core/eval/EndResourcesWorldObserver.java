package com.dwinovo.numen.core.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 末地前置资源按当前随身库存重算;采样经历仅留证据,不替代当前资源或证明重规划。 */
public final class EndResourcesWorldObserver implements WorldEvalObservers.Observer {
    private static final int MAX_EVENTS = 128;
    private static final int MAX_VISITED_CHUNKS = 256;
    private static final String[] EVENT_KINDS = {
            "dimension_change", "hurt", "sampled_dimension_change", "sampled_chunk_change", "inventory_change",
            "danger_observed", "danger_entered", "danger_cleared"};
    private final ExpeditionWorldObserver expedition = new ExpeditionWorldObserver(50);
    private final JsonArray events = new JsonArray();
    private final Map<String, Integer> eventCounts = new LinkedHashMap<>();
    private final Map<String, JsonObject> firstEvents = new LinkedHashMap<>();
    private final Map<String, JsonObject> visitedChunks = new LinkedHashMap<>();
    private JsonObject initialSample;
    private Map<String, Integer> previousInventory;
    private String previousDimension;
    private String previousChunk;
    private boolean previousSafe;
    private int previousEquivalents;
    private int eventsTotal;
    private boolean visitedChunksTruncated;
    private long lastLoadoutTick = -1;
    private int loadoutHeldTicks;

    /** 持续保持只由服务端每刻实际条件推进,客户端重复快照不贡献时间。 */
    @Override
    public void tick(NumenPlayer body) {
        long tick = body.serverLevel().getServer().overworld().getGameTime();
        if (tick == lastLoadoutTick) return;
        Loadout loadout = scan(body);
        JsonObject facts = loadout.facts();
        Map<String, Integer> carried = loadout.carried();
        boolean ready = facts.get("eye_equivalents").getAsInt() >= 16
                && facts.get("armor_ready").getAsBoolean() && facts.get("mining_tool_ready").getAsBoolean()
                && facts.get("shield_ready").getAsBoolean() && facts.get("food_nutrition").getAsInt() >= 80
                && facts.get("safe_state").getAsBoolean() && body.getHealth() >= 16
                && body.getFoodData().getFoodLevel() >= 16
                && carried.getOrDefault("minecraft:water_bucket", 0) >= 1
                && carried.getOrDefault("minecraft:torch", 0) >= 32
                && carried.getOrDefault("minecraft:crafting_table", 0) >= 1
                && carried.getOrDefault("minecraft:furnace", 0) >= 1;
        loadoutHeldTicks = !ready ? 0 : tick == lastLoadoutTick + 1 ? loadoutHeldTicks + 1 : 1;
        lastLoadoutTick = tick;
    }

    @Override
    public void dimensionChanged(NumenPlayer body) {
        JsonObject details = new JsonObject();
        details.addProperty("evidence_source", "native_dimension_change_hook");
        details.addProperty("to_dimension", body.level().dimension().location().toString());
        record("dimension_change", sample(body), details);
    }

    @Override
    public void hurt(NumenPlayer body, net.minecraft.world.damagesource.DamageSource source, float amount) {
        JsonObject details = new JsonObject();
        details.addProperty("evidence_source", "native_accepted_damage_event");
        details.addProperty("damage_type_message_id", source.type().msgId());
        details.addProperty("reported_amount_before_mitigation", amount);
        details.addProperty("health_after", body.getHealth());
        if (source.getEntity() != null) {
            JsonObject attacker = SurvivalWorldObserver.coordinates(source.getEntity().blockPosition());
            attacker.addProperty("uuid", source.getEntity().getUUID().toString());
            attacker.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).toString());
            details.add("source_entity", attacker);
        } else details.add("source_entity", JsonNull.INSTANCE);
        record("hurt", sample(body), details);
    }

    @Override
    public JsonObject observe(NumenPlayer body) {
        Loadout loadout = scan(body);
        JsonObject facts = loadout.facts();
        Map<String, Integer> carried = loadout.carried();
        JsonObject evidence = facts.getAsJsonObject("evidence");
        JsonObject resources = evidence.getAsJsonObject("resources");
        int equivalents = facts.get("eye_equivalents").getAsInt();
        facts.addProperty("loadout_held_ticks", loadoutHeldTicks);
        evidence.add("loadout_last_verified_game_tick", lastLoadoutTick < 0 ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(lastLoadoutTick));

        JsonObject sample = sample(body);
        String dimension = sample.get("dimension").getAsString();
        String chunk = dimension + ":" + sample.get("chunk_x").getAsInt() + ":" + sample.get("chunk_z").getAsInt();
        boolean safe = facts.get("safe_state").getAsBoolean();
        if (previousInventory == null) {
            initialSample = sample.deepCopy();
            initialSample.add("inventory", inventoryCounts(carried));
            initialSample.add("resources", resources.deepCopy());
            initialSample.add("safety", evidence.get("safety").deepCopy());
            if (!safe) record("danger_observed", sample, evidence.getAsJsonObject("safety"));
        } else {
            if (!dimension.equals(previousDimension)) {
                JsonObject change = new JsonObject();
                change.addProperty("from_dimension", previousDimension);
                record("sampled_dimension_change", sample, change);
            }
            if (!chunk.equals(previousChunk)) {
                JsonObject change = new JsonObject();
                change.addProperty("from_chunk", previousChunk);
                record("sampled_chunk_change", sample, change);
            }
            if (!carried.equals(previousInventory)) {
                JsonObject change = new JsonObject();
                Map<String, Integer> changedIds = new TreeMap<>(previousInventory);
                changedIds.putAll(carried);
                JsonArray changes = new JsonArray();
                for (String id : changedIds.keySet()) {
                    int before = previousInventory.getOrDefault(id, 0);
                    int after = carried.getOrDefault(id, 0);
                    if (before == after) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id);
                    item.addProperty("before", before);
                    item.addProperty("after", after);
                    item.addProperty("delta", after - before);
                    changes.add(item);
                }
                change.add("changes", changes);
                change.addProperty("eye_equivalents_before", previousEquivalents);
                change.addProperty("eye_equivalents_after", equivalents);
                record("inventory_change", sample, change);
            }
            if (previousSafe && !safe) {
                record("danger_observed", sample, evidence.getAsJsonObject("safety"));
                record("danger_entered", sample, evidence.getAsJsonObject("safety"));
            } else if (!previousSafe && safe) {
                record("danger_cleared", sample, evidence.getAsJsonObject("safety"));
            }
        }
        if (!visitedChunks.containsKey(chunk)) {
            if (visitedChunks.size() < MAX_VISITED_CHUNKS) visitedChunks.put(chunk, sample.deepCopy());
            else visitedChunksTruncated = true;
        }
        previousInventory = carried;
        previousDimension = dimension;
        previousChunk = chunk;
        previousSafe = safe;
        previousEquivalents = equivalents;

        evidence.addProperty("sampling", "dimension_change and hurt use native committed events; chunk, inventory and danger transitions are sampled and may miss events; inventory deltas do not identify an acquisition route");
        evidence.add("initial_sample", initialSample.deepCopy());
        evidence.add("events", events.deepCopy());
        evidence.addProperty("events_total", eventsTotal);
        evidence.addProperty("events_truncated", eventsTotal > MAX_EVENTS);
        JsonArray chunks = new JsonArray();
        visitedChunks.values().forEach(entry -> chunks.add(entry.deepCopy()));
        evidence.add("visited_chunks", chunks);
        evidence.addProperty("visited_chunks_truncated", visitedChunksTruncated);
        JsonObject coverage = new JsonObject();
        for (String kind : EVENT_KINDS) {
            JsonObject entry = new JsonObject();
            int count = eventCounts.getOrDefault(kind, 0);
            entry.addProperty("status", count > 0 ? "observed" : "not_covered");
            entry.addProperty("sampled_events", count);
            entry.add("first_event", firstEvents.containsKey(kind) ? firstEvents.get(kind).deepCopy() : JsonNull.INSTANCE);
            coverage.add(kind, entry);
        }
        for (String kind : new String[]{"risk_replanning", "resource_site_replanning"}) {
            JsonObject entry = new JsonObject();
            entry.addProperty("status", "not_measured");
            entry.addProperty("reason", "world snapshots cannot attribute planning; inspect the tool trace alongside recorded events");
            coverage.add(kind, entry);
        }
        evidence.add("coverage", coverage);
        return facts;
    }

    private record Loadout(JsonObject facts, Map<String, Integer> carried) {}

    private Loadout scan(NumenPlayer body) {
        JsonObject facts = expedition.observe(body);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        JsonArray resourceSlots = new JsonArray();
        Map<String, Integer> carried = new TreeMap<>();
        var inventory = body.getInventory();
        int eyes = 0;
        int pearls = 0;
        int powder = 0;
        int rods = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= inventory.items.size() && slot < inventory.items.size() + inventory.armor.size()) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            if (stack.is(Items.ENDER_EYE)) eyes += stack.getCount();
            else if (stack.is(Items.ENDER_PEARL)) pearls += stack.getCount();
            else if (stack.is(Items.BLAZE_POWDER)) powder += stack.getCount();
            else if (stack.is(Items.BLAZE_ROD)) rods += stack.getCount();
            else continue;
            JsonObject item = SurvivalWorldObserver.item(stack);
            item.addProperty("slot", slot);
            resourceSlots.add(item);
        }
        int equivalents = eyes + Math.min(pearls, powder + 2 * rods);
        facts.addProperty("ender_eyes", eyes);
        facts.addProperty("ender_pearls", pearls);
        facts.addProperty("blaze_powder", powder);
        facts.addProperty("blaze_rods", rods);
        facts.addProperty("pearl_units", eyes + pearls);
        facts.addProperty("blaze_units", eyes + powder + 2 * rods);
        facts.addProperty("eye_equivalents", equivalents);
        JsonObject resources = new JsonObject();
        resources.addProperty("formula", "ender_eyes + min(ender_pearls, blaze_powder + 2 * blaze_rods)");
        resources.addProperty("carried_scope", "inventory.items + inventory.offhand; excludes armor slots and external containers");
        JsonObject counts = new JsonObject();
        for (String key : new String[]{"ender_eyes", "ender_pearls", "blaze_powder", "blaze_rods",
                "pearl_units", "blaze_units", "eye_equivalents"}) counts.add(key, facts.get(key));
        resources.add("counts", counts);
        resources.add("slots", resourceSlots);
        evidence.add("resources", resources);

        return new Loadout(facts, carried);
    }

    private void record(String kind, JsonObject sample, JsonObject details) {
        JsonObject event = sample.deepCopy();
        event.addProperty("kind", kind);
        event.add("details", details.deepCopy());
        eventCounts.merge(kind, 1, Integer::sum);
        firstEvents.putIfAbsent(kind, event.deepCopy());
        eventsTotal++;
        if (events.size() < MAX_EVENTS) events.add(event);
    }

    private static JsonObject sample(NumenPlayer body) {
        JsonObject sample = SurvivalWorldObserver.coordinates(body.blockPosition());
        sample.addProperty("game_tick", body.serverLevel().getServer().overworld().getGameTime());
        sample.addProperty("dimension", body.level().dimension().location().toString());
        sample.addProperty("chunk_x", body.blockPosition().getX() >> 4);
        sample.addProperty("chunk_z", body.blockPosition().getZ() >> 4);
        return sample;
    }

    private static JsonObject inventoryCounts(Map<String, Integer> inventory) {
        JsonObject counts = new JsonObject();
        inventory.forEach(counts::addProperty);
        return counts;
    }
}
