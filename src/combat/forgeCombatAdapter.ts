import { createCommandMessage, type BridgeCommandAction, type BridgeSnapshotPayload } from '../clientBridge/clientProtocol.js';

export interface ForgeCombatAdapterOptions {
    clientId: string;
    timeoutMs?: number;
    requireEpicFight?: boolean;
    /** Reject a client snapshot that is known to be older than this bound. */
    maxSnapshotAgeMs?: number;
}

const ALLOWED_SKILL_SLOTS = new Set(['weapon_innate', 'weapon_skill_1', 'weapon_skill_2', 'weapon_skill_3']);

export function mapCombatAction(action: any, snapshot: BridgeSnapshotPayload | null): BridgeCommandAction[] {
    const target = action?.targetEntityId === undefined
        ? null
        : snapshot?.nearbyEntities?.find(entity => entity.entityId === action.targetEntityId) || null;
    switch (action?.kind) {
        // An attack without a target from the current bounded snapshot is unsafe.
        case 'attack': return target ? [{ kind: 'attack', mode: action.mode === 'hold' ? 'hold' : 'tap', targetEntityId: target.entityId }] : [];
        case 'use_skill': {
            const skillSlot = typeof action.skillSlot === 'string' && ALLOWED_SKILL_SLOTS.has(action.skillSlot)
                ? action.skillSlot
                : 'weapon_innate';
            return [{ kind: 'use_skill', skillSlot }];
        }
        case 'stop_all': return [{ kind: 'stop_all' }];
        case 'move': {
            const distance = target?.distance ?? 0;
            const forward = action.reason?.includes('retreat') ? -1 : distance > 3.5 ? 1 : 0;
            return [{ kind: 'move', forward, strafe: 0, sprint: forward > 0 && distance > 8 }];
        }
        case 'look': {
            if (!target) return [];
            const player = snapshot?.player?.position;
            const position = target.position;
            if (!player || !position) return [];
            const dx = position.x - player.x;
            const dy = position.y - player.y;
            const dz = position.z - player.z;
            const horizontal = Math.sqrt(dx * dx + dz * dz);
            const yaw = Math.atan2(-dx, dz) * 180 / Math.PI;
            const pitch = -Math.atan2(dy, horizontal) * 180 / Math.PI;
            return [{ kind: 'look', yaw, pitch }];
        }
        case 'select_target': return [];
        case 'resume_task': return [{ kind: 'stop_all' }];
        default: return [];
    }
}

export class ForgeCombatAdapter {
    private readonly bridge: any;
    private readonly options: Required<ForgeCombatAdapterOptions>;

    constructor(bridge: any, options: ForgeCombatAdapterOptions) {
        this.bridge = bridge;
        this.options = { timeoutMs: 3000, requireEpicFight: false, maxSnapshotAgeMs: 5000, ...options };
    }

    async execute(action: any, snapshot: BridgeSnapshotPayload | null, commandId: string) {
        const client = this.bridge.getClient?.(this.options.clientId);
        if (!client?.hello || !client.snapshot) return { status: 'unavailable', commandId };
        const epicFightAvailable = client.hello.payload.modCapabilities?.epicFight === true;
        if ((this.options.requireEpicFight || action?.kind === 'use_skill') && !epicFightAvailable) {
            return { status: 'unsupported', commandId };
        }
        if (client.lastSnapshotAt !== undefined && client.lastSnapshotAt !== null
            && Date.now() - client.lastSnapshotAt > this.options.maxSnapshotAgeMs) {
            return { status: 'stale_snapshot', commandId };
        }
        const effectiveSnapshot = snapshot || client.snapshot.payload;
        const actions = mapCombatAction(action, effectiveSnapshot);
        if (!actions.length) return { status: 'skipped', commandId };
        const command = createCommandMessage({ id: commandId, actions });
        try {
            const ack = await this.bridge.sendCommandAndWaitForAck(this.options.clientId, command, { timeoutMs: this.options.timeoutMs });
            if (ack?.payload?.commandId !== commandId) {
                return { status: 'rejected', commandId, message: 'Bridge acknowledgement command id mismatch', ack: ack?.payload };
            }
            return { status: ack.payload.status === 'ok' ? 'ok' : 'rejected', commandId, ack: ack.payload };
        } catch (error: any) {
            return { status: /Timed out/i.test(error?.message) ? 'timeout' : 'disconnected', commandId, message: String(error?.message || error) };
        }
    }
}
