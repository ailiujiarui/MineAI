// @ts-nocheck

const LOG_NAMES = [
    'oak_log',
    'birch_log',
    'spruce_log',
    'jungle_log',
    'acacia_log',
    'dark_oak_log',
    'mangrove_log',
    'cherry_log'
];

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

function countAny(inventoryCounts, itemNames) {
    return itemNames.reduce((sum, itemName) => sum + (inventoryCounts[itemName] || 0), 0);
}

function hasAnyNearby(nearbyBlocks, targets) {
    return nearbyBlocks.some(block => targets.includes(block));
}

function buildPrompt(lines) {
    return lines.join(' ');
}

export function decideAutonomyStage(snapshot) {
    const inventoryCounts = snapshot.inventoryCounts || {};
    const nearbyBlocks = snapshot.nearbyBlocks || [];
    const logCount = countAny(inventoryCounts, LOG_NAMES);
    const plankCount = countAny(inventoryCounts, PLANK_NAMES);
    const stickCount = inventoryCounts.stick || 0;
    const hasIronPickaxe = (inventoryCounts.iron_pickaxe || 0) > 0;
    const hasWoodenPickaxe = (inventoryCounts.wooden_pickaxe || 0) > 0 || (inventoryCounts.stone_pickaxe || 0) > 0 || hasIronPickaxe;
    const hasStonePickaxe = (inventoryCounts.stone_pickaxe || 0) > 0 || hasIronPickaxe;
    const hasFurnace = (inventoryCounts.furnace || 0) > 0;
    const torchCount = inventoryCounts.torch || 0;
    const hasShield = (inventoryCounts.shield || 0) > 0;

    if (!hasWoodenPickaxe) {
        if (logCount === 0 && plankCount === 0 && stickCount === 0) {
            return {
                stage: 'gather_wood',
                goalPrompt: buildPrompt([
                    'Collect logs and bootstrap early survival until the wooden tool stage is ready.',
                    'First inspect with !stats, !inventory, and !nearbyBlocks.',
                    hasAnyNearby(nearbyBlocks, LOG_NAMES)
                        ? 'Use !collectBlocks("oak_log", 4) or another nearby log type immediately.'
                        : 'Use !exploreForBlock("oak_log", 64, 24) and then !collectBlocks("oak_log", 4).',
                    'After collecting logs, craft planks and sticks with !craftRecipe before moving on.',
                    'Use one command at a time and prefer reliable built-in commands over freeform code.'
                ])
            };
        }

        return {
            stage: 'craft_wooden_pickaxe',
            goalPrompt: buildPrompt([
                'Finish the wooden tool setup cleanly and craft a wooden pickaxe.',
                'Check resources with !inventory and !craftable.',
                'If needed, craft planks and sticks with !craftRecipe, then craft the tool with !craftRecipe("wooden_pickaxe", 1).',
                'If crafting fails because materials are short, gather only the missing wood and retry.',
                'Use one command at a time and avoid unnecessary chatting.'
            ])
        };
    }

    if (!hasStonePickaxe) {
        return {
            stage: 'upgrade_to_stone',
            goalPrompt: buildPrompt([
                'Upgrade from wood to stone tools and finish a stone pickaxe.',
                'Check !inventory and !nearbyBlocks first.',
                hasAnyNearby(nearbyBlocks, ['stone', 'cobblestone'])
                    ? 'If stone is already nearby, you may go straight to !collectBlocks("stone", 3), otherwise use !searchForBlock("stone", 64) first.'
                    : 'Use !searchForBlock("stone", 64) and then !collectBlocks("stone", 3).',
                'If a crafting table is missing, craft it with !craftRecipe("crafting_table", 1).',
                'Finish with !craftRecipe("stone_pickaxe", 1).',
                'If terrain becomes unsafe, stabilize before digging deeper.'
            ])
        };
    }

    if (hasIronPickaxe && hasShield) {
        return {
            stage: 'survival_steady_state',
            goalPrompt: buildPrompt([
                'Survive efficiently in a stable steady state after the first iron milestone.',
                'Regularly inspect with !stats, !inventory, and !nearbyBlocks.',
                'Keep food, torches, and core tools stocked; gather more with built-in commands when buffers get low.',
                'Use !goToSurface when underground pressure gets too high or navigation becomes unsafe.',
                'Prioritize safe, efficient progress over exploration for its own sake.'
            ])
        };
    }

    if (!hasFurnace || torchCount < 8) {
        return {
            stage: 'secure_furnace_and_light',
            goalPrompt: buildPrompt([
                'Secure the furnace and torches lighting milestone.',
                'Check !inventory, !nearbyBlocks, and !craftable.',
                'If you lack a furnace, gather cobblestone and craft it with !craftRecipe("furnace", 1).',
                'If fuel is missing, use !searchForBlock("coal_ore", 64) or collect nearby coal.',
                'Smelt or craft as needed, then make lighting with !craftRecipe("torch", 2) or more until you have a safe stack.',
                'Prefer safe mining and keep enough food before going underground.'
            ])
        };
    }

    if (!hasIronPickaxe || !hasShield) {
        return {
            stage: 'reach_iron',
            goalPrompt: buildPrompt([
                'Complete the iron progression milestone and finish an iron pickaxe plus shield.',
                'Start with !stats, !inventory, and !nearbyBlocks.',
                hasAnyNearby(nearbyBlocks, ['iron_ore', 'deepslate_iron_ore'])
                    ? 'Mine visible iron with !collectBlocks("iron_ore", 3) if possible.'
                    : 'Search with !searchForBlock("iron_ore", 96); if deep underground becomes risky, use !goToSurface first and re-enter safely.',
                'Process ore with !smeltItem("iron_ore", 3).',
                'Craft the upgrades with !craftRecipe("shield", 1) and !craftRecipe("iron_pickaxe", 1).',
                'If hunger gets low, recover food before committing to deeper mining.'
            ])
        };
    }

    return {
        stage: 'survival_steady_state',
        goalPrompt: buildPrompt([
            'Maintain a stable survival loop after the first iron milestone.',
            'Regularly inspect with !stats, !inventory, and !nearbyBlocks.',
            'Keep food, torches, and core tools stocked; gather more with built-in commands when buffers get low.',
            'Use !goToSurface when underground pressure gets too high or navigation becomes unsafe.',
            'Prioritize safe, efficient progress over exploration for its own sake.'
        ])
    };
}
