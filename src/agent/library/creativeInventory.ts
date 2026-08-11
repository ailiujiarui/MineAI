// @ts-nocheck
import * as mc from '../../utils/mcdata.js';

export const MAX_CREATIVE_ITEM_COUNT = 2304;

export function canProvisionCreative(bot) {
    return bot?.game?.gameMode === 'creative' && bot?.restrict_to_inventory !== true;
}

function inventoryBounds(bot) {
    const start = Number.isInteger(bot?.inventory?.inventoryStart)
        ? bot.inventory.inventoryStart
        : 9;
    const end = Number.isInteger(bot?.inventory?.inventoryEnd)
        ? bot.inventory.inventoryEnd
        : Math.min(bot?.inventory?.slots?.length ?? 45, 45);
    return { start, end };
}

function orderedSlots(bot) {
    const { start, end } = inventoryBounds(bot);
    const hotbarStart = Number.isInteger(bot?.inventory?.hotbarStart)
        ? bot.inventory.hotbarStart
        : end - 9;
    const activeHotbar = hotbarStart + Number(bot?.quickBarSlot ?? 0);
    const normal = [];
    const hotbar = [];
    for (let slot = start; slot < end; slot += 1) {
        if (slot >= hotbarStart && slot < end) hotbar.push(slot);
        else normal.push(slot);
    }
    return [...normal, ...hotbar.filter((slot) => slot !== activeHotbar), ...hotbar.filter((slot) => slot === activeHotbar)];
}

function isMergeable(slotItem, requested) {
    return slotItem
        && slotItem.type === requested.type
        && Number(slotItem.metadata ?? 0) === Number(requested.metadata ?? 0)
        && !slotItem.nbt;
}

export function planCreativeMaterialization(bot, itemName, count, createItem = mc.makeItem) {
    if (!canProvisionCreative(bot)) return { ok: false, reason: 'restricted' };
    if (!Number.isInteger(count) || count < 1 || count > MAX_CREATIVE_ITEM_COUNT) {
        return { ok: false, reason: 'invalid-count' };
    }

    let requested;
    try {
        requested = createItem(itemName, 1);
    } catch {
        requested = null;
    }
    if (!requested || requested.type == null) return { ok: false, reason: 'invalid-item' };

    const stackSize = Math.max(1, Number(requested.stackSize || 64));
    const slots = bot.inventory.slots || [];
    const writes = [];
    let remaining = count;

    for (const slot of orderedSlots(bot)) {
        const existing = slots[slot];
        if (!isMergeable(existing, requested) || existing.count >= stackSize) continue;
        const added = Math.min(stackSize - existing.count, remaining);
        writes.push({ slot, item: createItem(itemName, existing.count + added), original: existing });
        remaining -= added;
        if (remaining === 0) break;
    }
    if (remaining > 0) {
        for (const slot of orderedSlots(bot)) {
            if (slots[slot] || writes.some((write) => write.slot === slot)) continue;
            const added = Math.min(stackSize, remaining);
            writes.push({ slot, item: createItem(itemName, added), original: null });
            remaining -= added;
            if (remaining === 0) break;
        }
    }
    if (remaining > 0) return { ok: false, reason: 'inventory-full' };
    return { ok: true, itemName, count, writes };
}

async function restoreWrites(bot, writes) {
    for (const write of [...writes].reverse()) {
        await bot.creative.setInventorySlot(write.slot, write.original || null);
    }
}

export async function materializeCreativeItem(bot, itemName, count, options = {}) {
    const plan = planCreativeMaterialization(bot, itemName, count, options.createItem || mc.makeItem);
    if (!plan.ok) return plan;

    const applied = [];
    try {
        for (const write of plan.writes) {
            await bot.creative.setInventorySlot(write.slot, write.item);
            applied.push(write);
        }
        return { ok: true, itemName, count, slots: plan.writes.map((write) => write.slot) };
    } catch (error) {
        try { await restoreWrites(bot, applied); } catch {}
        return { ok: false, reason: 'write-failed', error };
    }
}

export async function withTemporaryCreativeItem(bot, itemName, callback, options = {}) {
    const existing = bot.inventory.findInventoryItem(itemName);
    if (existing) return callback(existing, false);
    if (!canProvisionCreative(bot)) return { ok: false, reason: 'missing-item' };

    const createItem = options.createItem || mc.makeItem;
    let item;
    try { item = createItem(itemName, 1); } catch { item = null; }
    if (!item || item.type == null) return { ok: false, reason: 'invalid-item' };

    const slots = bot.inventory.slots || [];
    const { end } = inventoryBounds(bot);
    const hotbarStart = Number.isInteger(bot?.inventory?.hotbarStart)
        ? bot.inventory.hotbarStart
        : end - 9;
    const activeHotbar = hotbarStart + Number(bot.quickBarSlot ?? 0);
    const candidates = orderedSlots(bot).filter((slot) => slot >= hotbarStart && slot < end && slot !== activeHotbar);
    const tempSlot = candidates.find((slot) => !slots[slot]) ?? candidates[0];
    if (tempSlot == null) return { ok: false, reason: 'inventory-full' };

    const original = slots[tempSlot] || null;
    const originalQuickBarSlot = Number(bot.quickBarSlot ?? 0);
    await bot.creative.setInventorySlot(tempSlot, item);
    if (typeof bot.setQuickBarSlot === 'function') {
        bot.setQuickBarSlot(tempSlot - hotbarStart);
    }
    const provisioned = bot.inventory.slots?.[tempSlot] || item;
    try {
        return await callback(provisioned, true);
    } finally {
        await bot.creative.setInventorySlot(tempSlot, original);
        if (typeof bot.setQuickBarSlot === 'function' && bot.quickBarSlot !== originalQuickBarSlot) {
            bot.setQuickBarSlot(originalQuickBarSlot);
        }
    }
}
