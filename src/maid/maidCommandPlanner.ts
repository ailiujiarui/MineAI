import { selectMaidWork } from './maidPlanner.js';

export function planMaidCommand(input) {
    const maid = input?.maid;
    if (!maid || !maid.modes || !maid.snapshot) {
        return null;
    }

    if (maid.snapshot.hostileCount > 0 && maid.modes.includes('defend')) {
        return {
            decision: {
                mode: 'defend',
                reason: `Hostile pressure detected (${maid.snapshot.hostileCount})`
            },
            command: '!attack("hostile")'
        };
    }

    if ((maid.snapshot.readyCrops || 0) > 0 && maid.cropTarget && maid.modes.includes('harvest')) {
        const amount = Math.max(1, maid.snapshot.readyCrops || 1);
        return {
            decision: {
                mode: 'harvest',
                reason: `Ready crops available (${maid.snapshot.readyCrops})`
            },
            command: `!collectBlocks("${maid.cropTarget}", ${amount})`
        };
    }

    if (maid.storeItemName && maid.hasNearbyChest && maid.modes.includes('store-items')) {
        return {
            decision: {
                mode: 'store-items',
                reason: 'Store harvested output before the next farm step'
            },
            command: `!putInChest("${maid.storeItemName}", 64)`
        };
    }

    if ((maid.snapshot.emptyFarmland || 0) > 0 && maid.replantTarget && maid.modes.includes('replant')) {
        return {
            decision: {
                mode: 'replant',
                reason: `Empty farmland needs reseeding (${maid.snapshot.emptyFarmland})`
            },
            command: `!tillAndSow(${maid.replantTarget.x}, ${maid.replantTarget.y}, ${maid.replantTarget.z}, "${maid.replantTarget.seedType}")`
        };
    }

    const decision = selectMaidWork({
        modes: maid.modes,
        snapshot: maid.snapshot
    });

    if (decision.mode === 'follow' && maid.nearestPlayerName) {
        return {
            decision,
            command: `!followPlayer("${maid.nearestPlayerName}", 4)`
        };
    }

    if (decision.mode === 'patrol') {
        return {
            decision,
            command: '!moveAway(6)'
        };
    }

    return {
        decision,
        command: null
    };
}
