// @ts-nocheck
import { createBlockLookup, selectBestMiningTarget } from './baritoneMining.js';

const PLANK_NAMES = [
    'oak_planks',
    'birch_planks',
    'spruce_planks',
    'jungle_planks',
    'acacia_planks',
    'dark_oak_planks',
    'mangrove_planks',
    'cherry_planks'
];

const LOG_TO_PLANK = {
    oak_log: 'oak_planks',
    birch_log: 'birch_planks',
    spruce_log: 'spruce_planks',
    jungle_log: 'jungle_planks',
    acacia_log: 'acacia_planks',
    dark_oak_log: 'dark_oak_planks',
    mangrove_log: 'mangrove_planks',
    cherry_log: 'cherry_planks'
};

const LOG_NAMES = Object.keys(LOG_TO_PLANK);

function countAny(inventoryCounts, itemNames) {
    return itemNames.reduce((sum, itemName) => sum + (inventoryCounts[itemName] || 0), 0);
}

function hasNearby(nearbyBlocks, blockName) {
    return (nearbyBlocks || []).includes(blockName);
}

export function planAutonomyCommand(stage, snapshot) {
    const inventoryCounts = snapshot.inventoryCounts || {};
    const nearbyBlocks = snapshot.nearbyBlocks || [];
    const knownResourceLocations = snapshot.knownResourceLocations || [];
    const blacklistedResourceKeys = new Set(snapshot.blacklistedResourceKeys || []);
    const origin = snapshot.position || { x: 0, y: 0, z: 0 };
    const logCount = countAny(inventoryCounts, LOG_NAMES);
    const plankCount = countAny(inventoryCounts, PLANK_NAMES);
    const stickCount = inventoryCounts.stick || 0;
    const torchCount = inventoryCounts.torch || 0;
    const coalCount = inventoryCounts.coal || 0;
    const charcoalCount = inventoryCounts.charcoal || 0;
    const ironOreCount = inventoryCounts.iron_ore || 0;
    const rawIronCount = inventoryCounts.raw_iron || 0;
    const ironIngotCount = inventoryCounts.iron_ingot || 0;
    const availableLogType = Object.keys(LOG_TO_PLANK).find((itemName) => (inventoryCounts[itemName] || 0) > 0);
    const nearbyLogType = LOG_NAMES.find((itemName) => hasNearby(nearbyBlocks, itemName));
    const logLookup = createBlockLookup(LOG_NAMES);
    const oreLookup = createBlockLookup(['iron_ore', 'deepslate_iron_ore']);
    const bestLogTarget = selectBestMiningTarget(origin, knownResourceLocations, logLookup, blacklistedResourceKeys);
    const bestOreTarget = selectBestMiningTarget(origin, knownResourceLocations, oreLookup, blacklistedResourceKeys);

    if (stage === 'gather_wood') {
        if (nearbyLogType) {
            return `!collectBlocks("${nearbyLogType}", 4)`;
        }
        if (bestLogTarget) {
            return `!collectBlocks("${bestLogTarget.blockName}", 4)`;
        }
        return '!exploreForBlock("oak_log", 64, 24)';
    }

    if (stage === 'craft_wooden_pickaxe') {
        if (stickCount >= 2 && plankCount >= 3) {
            return '!craftRecipe("wooden_pickaxe", 1)';
        }
        if (stickCount < 2 && plankCount >= 2) {
            return '!craftRecipe("stick", 1)';
        }
        if (plankCount === 0 && availableLogType) {
            return `!craftRecipe("${LOG_TO_PLANK[availableLogType]}", 1)`;
        }
    }

    if (stage === 'upgrade_to_stone') {
        if ((inventoryCounts.stone_pickaxe || 0) === 0 && (inventoryCounts.cobblestone || 0) >= 3 && stickCount >= 2) {
            return '!craftRecipe("stone_pickaxe", 1)';
        }
        if ((inventoryCounts.stone_pickaxe || 0) === 0 && stickCount < 2 && plankCount >= 2) {
            return '!craftRecipe("stick", 1)';
        }
        if ((inventoryCounts.stone_pickaxe || 0) === 0 && hasNearby(nearbyBlocks, 'stone')) {
            return '!collectBlocks("stone", 3)';
        }
        if ((inventoryCounts.stone_pickaxe || 0) === 0) {
            return '!searchForBlock("stone", 64)';
        }
    }

    if (stage === 'secure_furnace_and_light') {
        if ((inventoryCounts.furnace || 0) === 0 && (inventoryCounts.cobblestone || 0) >= 8) {
            return '!craftRecipe("furnace", 1)';
        }

        if ((inventoryCounts.furnace || 0) === 0) {
            return '!collectBlocks("stone", 8)';
        }

        if (coalCount + charcoalCount === 0) {
            if (hasNearby(nearbyBlocks, 'coal_ore')) {
                return '!collectBlocks("coal_ore", 3)';
            }
            return '!searchForBlock("coal_ore", 64)';
        }

        if (torchCount < 8 && stickCount < 2 && plankCount < 2 && availableLogType) {
            return `!craftRecipe("${LOG_TO_PLANK[availableLogType]}", 1)`;
        }

        if (torchCount < 8 && stickCount >= 2 && (coalCount + charcoalCount) >= 1) {
            return '!craftRecipe("torch", 2)';
        }

        if (torchCount < 8 && stickCount < 2 && plankCount >= 2) {
            return '!craftRecipe("stick", 1)';
        }
    }

    if (stage === 'reach_iron') {
        if (rawIronCount >= 1 && coalCount + charcoalCount === 0) {
            if (hasNearby(nearbyBlocks, 'coal_ore')) {
                return '!collectBlocks("coal_ore", 3)';
            }
            return '!searchForBlock("coal_ore", 64)';
        }

        if (ironOreCount >= 3 && coalCount + charcoalCount === 0) {
            if (hasNearby(nearbyBlocks, 'coal_ore')) {
                return '!collectBlocks("coal_ore", 3)';
            }
            return '!searchForBlock("coal_ore", 64)';
        }

        if ((inventoryCounts.shield || 0) === 0 && ironIngotCount >= 1 && plankCount >= 6) {
            return '!craftRecipe("shield", 1)';
        }

        if ((inventoryCounts.iron_pickaxe || 0) === 0 && ironIngotCount >= 3 && stickCount >= 2) {
            return '!craftRecipe("iron_pickaxe", 1)';
        }

        if ((inventoryCounts.iron_pickaxe || 0) === 0 && stickCount < 2 && plankCount >= 2) {
            return '!craftRecipe("stick", 1)';
        }

        if (ironIngotCount === 0 && rawIronCount >= 1) {
            return `!smeltItem("raw_iron", ${rawIronCount})`;
        }

        if (ironIngotCount === 0 && ironOreCount >= 3) {
            return '!smeltItem("iron_ore", 3)';
        }

        if (ironOreCount < 3) {
            if (hasNearby(nearbyBlocks, 'iron_ore')) {
                return '!collectBlocks("iron_ore", 3)';
            }
            if (hasNearby(nearbyBlocks, 'deepslate_iron_ore')) {
                return '!collectBlocks("deepslate_iron_ore", 3)';
            }
            if (bestOreTarget) {
                return `!collectBlocks("${bestOreTarget.blockName}", 3)`;
            }
            return '!searchForBlock("iron_ore", 96)';
        }
    }

    return null;
}
