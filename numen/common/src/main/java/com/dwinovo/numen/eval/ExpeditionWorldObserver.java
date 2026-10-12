package com.dwinovo.numen.eval;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.eval.WorldEvalObservers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 远征准备的实际装备与补给:四件甲必须穿戴,各件耐久独立检查,不从原料推算成品。 */
public final class ExpeditionWorldObserver implements WorldEvalObservers.Observer {
    private final int minimumDurabilityPercent;

    public ExpeditionWorldObserver() {
        this(80);
    }

    ExpeditionWorldObserver(int minimumDurabilityPercent) {
        this.minimumDurabilityPercent = minimumDurabilityPercent;
    }

    @Override
    public JsonObject observe(NumenPlayer body) {
        JsonObject facts = new JsonObject();
        JsonObject evidence = new JsonObject();
        JsonArray inventoryEvidence = new JsonArray();
        var inventory = body.getInventory();
        boolean pickaxe = false;
        boolean shield = false;
        int nutrition = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = itemEvidence(stack);
            item.addProperty("slot", slot);
            boolean carried = slot < inventory.items.size()
                    || slot >= inventory.items.size() + inventory.armor.size();
            int food = carried ? SurvivalWorldObserver.safeNutrition(stack) : 0;
            item.addProperty("safe_nutrition", food);
            inventoryEvidence.add(item);
            if (!carried) continue;
            nutrition += food;
            pickaxe |= ready(stack, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
            shield |= ready(stack, Items.SHIELD);
        }

        JsonObject armorEvidence = new JsonObject();
        boolean armor = true;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = body.getItemBySlot(slot);
            boolean valid = switch (slot) {
                case HEAD -> ready(stack, Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
                case CHEST -> ready(stack, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
                case LEGS -> ready(stack, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
                case FEET -> ready(stack, Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
                default -> throw new IllegalStateException("Not an armor slot: " + slot);
            };
            armor &= valid;
            JsonObject item = itemEvidence(stack);
            item.addProperty("ready", valid);
            armorEvidence.add(slot.getName(), item);
        }

        JsonObject safety = FreeSurvivalWorldObserver.safety(body);
        facts.addProperty("mining_tool_ready", pickaxe);
        facts.addProperty("shield_ready", shield);
        facts.addProperty("armor_ready", armor);
        facts.addProperty("food_nutrition", nutrition);
        facts.addProperty("safe_state", safety.get("valid").getAsBoolean());
        evidence.addProperty("scanned_game_tick", body.serverLevel().getServer().overworld().getGameTime());
        evidence.addProperty("dimension", body.level().dimension().location().toString());
        evidence.add("inventory", inventoryEvidence);
        evidence.add("armor", armorEvidence);
        evidence.add("safety", safety);
        facts.add("evidence", evidence);
        return facts;
    }

    private boolean ready(ItemStack stack, Item... allowed) {
        if (stack.isEmpty() || !stack.isDamageableItem()
                || (long) (stack.getMaxDamage() - stack.getDamageValue()) * 100
                < (long) stack.getMaxDamage() * minimumDurabilityPercent) {
            return false;
        }
        for (Item item : allowed) if (stack.is(item)) return true;
        return false;
    }

    private static JsonObject itemEvidence(ItemStack stack) {
        JsonObject item = SurvivalWorldObserver.item(stack);
        if (stack.isDamageableItem()) item.addProperty("max_durability", stack.getMaxDamage());
        return item;
    }
}
