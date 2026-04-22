export type MaidWorkMode =
    | 'harvest'
    | 'replant'
    | 'store-items'
    | 'follow'
    | 'patrol'
    | 'defend';

export type MaidWorkSnapshot = {
    readyCrops: number;
    emptyFarmland: number;
    hostileCount: number;
    inventoryUtilization: number;
    playerDistance: number;
};

export type MaidWorkDecision = {
    mode: MaidWorkMode;
    reason: string;
    target?: string;
};

export type MaidTaskStatus = 'idle' | 'running' | 'blocked' | 'complete';

export type MaidTaskState = {
    status: MaidTaskStatus;
    current: MaidWorkDecision | null;
    blockageReason: string | null;
};
