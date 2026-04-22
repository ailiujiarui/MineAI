import { type MaidTaskState, type MaidWorkDecision, type MaidWorkMode, type MaidWorkSnapshot } from './maidTypes.js';

export function selectMaidWork(input: { modes: MaidWorkMode[]; snapshot: MaidWorkSnapshot }): MaidWorkDecision {
    const { modes, snapshot } = input;
    const hasMode = (mode: MaidWorkMode) => modes.includes(mode);

    if (snapshot.hostileCount > 0 && hasMode('defend')) {
        return {
            mode: 'defend',
            reason: `Hostile pressure detected (${snapshot.hostileCount})`
        };
    }

    if (snapshot.readyCrops > 0 && hasMode('harvest')) {
        return {
            mode: 'harvest',
            reason: `Ready crops available (${snapshot.readyCrops})`
        };
    }

    if (snapshot.emptyFarmland > 0 && hasMode('replant')) {
        return {
            mode: 'replant',
            reason: `Empty farmland needs reseeding (${snapshot.emptyFarmland})`
        };
    }

    if (snapshot.inventoryUtilization >= 0.85 && hasMode('store-items')) {
        return {
            mode: 'store-items',
            reason: 'Inventory is nearly full'
        };
    }

    if (snapshot.playerDistance > 8 && hasMode('follow')) {
        return {
            mode: 'follow',
            reason: 'Player is outside comfortable escort range',
            target: 'player'
        };
    }

    if (hasMode('patrol')) {
        return {
            mode: 'patrol',
            reason: 'No urgent work; maintain area coverage'
        };
    }

    return {
        mode: modes[0] || 'follow',
        reason: 'Fallback task selection',
        target: modes[0] === 'follow' ? 'player' : undefined
    };
}

export function createMaidTaskRuntime(initial?: Partial<MaidTaskState>) {
    const state: MaidTaskState = {
        status: initial?.status || 'idle',
        current: initial?.current || null,
        blockageReason: initial?.blockageReason || null
    };

    return {
        assign(decision: MaidWorkDecision) {
            state.current = decision;
            state.status = 'running';
            state.blockageReason = null;
        },
        markBlocked(reason: string) {
            state.status = 'blocked';
            state.blockageReason = reason;
        },
        markComplete() {
            state.status = 'complete';
            state.blockageReason = null;
        },
        reset() {
            state.status = 'idle';
            state.current = null;
            state.blockageReason = null;
        },
        getState() {
            return { ...state };
        }
    };
}
