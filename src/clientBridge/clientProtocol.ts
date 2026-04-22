export const CLIENT_BRIDGE_PROTOCOL_VERSION = 1

export type CombatMode = 'vanilla' | 'epicfight'

export interface BridgeNearbyEntity {
    entityId: number
    name: string
    type: 'hostile' | 'neutral' | 'player' | 'unknown'
    distance: number
    position: {
        x: number
        y: number
        z: number
    }
}

export interface BridgeHelloPayload {
    clientId: string
    minecraftVersion: string
    loader: 'forge'
    modCapabilities: {
        epicFight: boolean
        slashBlade: boolean
    }
}

export interface BridgeSnapshotPayload {
    tick: number
    player: {
        name: string
        health: number
        food: number
        position: {
            x: number
            y: number
            z: number
        }
        yaw: number
        pitch: number
        combatMode: CombatMode
    }
    nearbyEntities: BridgeNearbyEntity[]
}

export type BridgeCommandAction =
    | { kind: 'move'; forward: number; strafe: number; jump?: boolean; sprint?: boolean }
    | { kind: 'look'; yaw: number; pitch: number }
    | { kind: 'attack'; mode: 'tap' | 'hold'; targetEntityId?: number }
    | { kind: 'use_skill'; skillSlot: string }
    | { kind: 'equip_hotbar'; hotbarIndex: number }
    | { kind: 'stop_all' }

export interface BridgeCommandPayload {
    id: string
    actions: BridgeCommandAction[]
}

export interface BridgeAckPayload {
    commandId: string
    status: 'ok' | 'error'
    detail?: string
}

export function createHelloMessage(payload: BridgeHelloPayload) {
    return {
        type: 'hello' as const,
        protocolVersion: CLIENT_BRIDGE_PROTOCOL_VERSION,
        payload
    }
}

export function createSnapshotMessage(payload: BridgeSnapshotPayload) {
    return {
        type: 'snapshot' as const,
        protocolVersion: CLIENT_BRIDGE_PROTOCOL_VERSION,
        payload
    }
}

export function createCommandMessage(payload: BridgeCommandPayload) {
    return {
        type: 'command' as const,
        protocolVersion: CLIENT_BRIDGE_PROTOCOL_VERSION,
        payload
    }
}

export function createAckMessage(payload: BridgeAckPayload) {
    return {
        type: 'ack' as const,
        protocolVersion: CLIENT_BRIDGE_PROTOCOL_VERSION,
        payload
    }
}
