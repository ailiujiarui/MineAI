// @ts-nocheck
import { canProvisionCreative, materializeCreativeItem } from './creativeInventory.js';

const SUPPORTED_ARMOR_MATERIALS = new Set([
    'leather',
    'chainmail',
    'iron',
    'golden',
    'diamond',
    'netherite'
]);

export function resolveArmorSetItems(material) {
    const raw = String(material || '').trim().toLowerCase();
    const normalized = raw === 'gold' ? 'golden' : raw === 'chain' ? 'chainmail' : raw;
    if (!SUPPORTED_ARMOR_MATERIALS.has(normalized)) return null;
    return [
        `${normalized}_helmet`,
        `${normalized}_chestplate`,
        `${normalized}_leggings`,
        `${normalized}_boots`
    ];
}

function inventoryCount(bot, itemName) {
    return (bot?.inventory?.slots || []).reduce((count, item) => {
        return count + (item?.name === itemName ? Number(item.count || 0) : 0);
    }, 0);
}

function emptyInventorySlots(bot) {
    const slots = bot?.inventory?.slots || [];
    const start = Number.isInteger(bot?.inventory?.inventoryStart)
        ? bot.inventory.inventoryStart
        : 9;
    const end = Number.isInteger(bot?.inventory?.inventoryEnd)
        ? bot.inventory.inventoryEnd
        : Math.min(slots.length, 45);
    let empty = 0;
    for (let slot = start; slot < end; slot += 1) {
        if (!slots[slot]) empty += 1;
    }
    return empty;
}

export async function giveArmorSet(bot, playerName, material, options = {}) {
    const items = resolveArmorSetItems(material);
    const log = options.log || (() => {});
    const materialize = options.materialize || materializeCreativeItem;
    const giveItem = options.giveItem;

    if (!items) {
        log(`Unsupported armor material: ${material}.`);
        return false;
    }
    if (!bot?.players?.[playerName]?.entity) {
        log(`Could not find ${playerName}.`);
        return false;
    }
    if (typeof giveItem !== 'function') {
        throw new Error('giveArmorSet requires a giveItem implementation.');
    }

    const missing = items.filter((itemName) => inventoryCount(bot, itemName) < 1);
    if (missing.length > 0) {
        if (!canProvisionCreative(bot)) {
            log(`Missing armor pieces: ${missing.join(', ')}.`);
            return false;
        }
        if (emptyInventorySlots(bot) < missing.length) {
            log(`Not enough inventory space to prepare the full ${material} armor set.`);
            return false;
        }
        for (const itemName of missing) {
            const result = await materialize(bot, itemName, 1);
            if (!result?.ok) {
                log(`Failed to prepare ${itemName}: ${result?.reason || 'unknown error'}.`);
                return false;
            }
        }
    }

    const completed = [];
    for (const itemName of items) {
        const given = await giveItem(bot, itemName, playerName, 1);
        if (!given) {
            const remaining = items.slice(completed.length);
            log(`Armor set transfer stopped. Completed: ${completed.join(', ') || 'none'}. Remaining: ${remaining.join(', ')}.`);
            return false;
        }
        completed.push(itemName);
    }
    log(`Gave ${playerName} a full ${material} armor set.`);
    return true;
}
