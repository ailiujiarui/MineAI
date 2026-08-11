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

export interface BridgeNearbyBlock {
    name: string
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
    nearbyBlocks?: BridgeNearbyBlock[]
    targetedBlock?: BridgeNearbyBlock | null
}

export type BridgeCommandAction =
    | { kind: 'move'; forward: number; strafe: number; jump?: boolean; sprint?: boolean }
    | { kind: 'look'; yaw: number; pitch: number }
    | { kind: 'attack'; mode: 'tap' | 'hold'; targetEntityId?: number }
    | { kind: 'use_skill'; skillSlot: string }
    | { kind: 'equip_hotbar'; hotbarIndex: number }
    | { kind: 'mine_block'; position: { x: number; y: number; z: number } }
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

type BridgeEnvelopeType = 'hello' | 'snapshot' | 'command' | 'ack'

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null
}

function isFiniteNumber(value: unknown): value is number {
    return typeof value === 'number' && Number.isFinite(value)
}

function isPosition(value: unknown): value is { x: number; y: number; z: number } {
    return isRecord(value)
        && isFiniteNumber(value.x)
        && isFiniteNumber(value.y)
        && isFiniteNumber(value.z)
}

function isNearbyBlock(value: unknown): value is BridgeNearbyBlock {
    return isRecord(value)
        && typeof value.name === 'string'
        && isFiniteNumber(value.distance)
        && value.distance >= 0
        && isPosition(value.position)
}

function hasValidEnvelope(value: unknown, type: BridgeEnvelopeType): value is Record<string, unknown> & {
    type: BridgeEnvelopeType
    protocolVersion: number
    payload: Record<string, unknown>
} {
    return isRecord(value)
        && value.type === type
        && value.protocolVersion === CLIENT_BRIDGE_PROTOCOL_VERSION
        && isRecord(value.payload)
}

export function isBridgeHelloMessage(value: unknown): value is ReturnType<typeof createHelloMessage> {
    if (!hasValidEnvelope(value, 'hello')) return false
    const payload = value.payload
    const capabilities = payload.modCapabilities
    return typeof payload.clientId === 'string'
        && payload.clientId.trim().length > 0
        && typeof payload.minecraftVersion === 'string'
        && payload.minecraftVersion.trim().length > 0
        && payload.loader === 'forge'
        && isRecord(capabilities)
        && typeof capabilities.epicFight === 'boolean'
        && typeof capabilities.slashBlade === 'boolean'
}

export function isBridgeSnapshotMessage(value: unknown): value is ReturnType<typeof createSnapshotMessage> {
    if (!hasValidEnvelope(value, 'snapshot')) return false
    const payload = value.payload
    const player = payload.player
    return isFiniteNumber(payload.tick)
        && isRecord(player)
        && typeof player.name === 'string'
        && isFiniteNumber(player.health)
        && isFiniteNumber(player.food)
        && isPosition(player.position)
        && isFiniteNumber(player.yaw)
        && isFiniteNumber(player.pitch)
        && (player.combatMode === 'vanilla' || player.combatMode === 'epicfight')
        && Array.isArray(payload.nearbyEntities)
        && payload.nearbyEntities.every((entity) => isRecord(entity)
            && isFiniteNumber(entity.entityId)
            && typeof entity.name === 'string'
            && (entity.type === 'hostile' || entity.type === 'neutral' || entity.type === 'player' || entity.type === 'unknown')
            && isFiniteNumber(entity.distance)
            && isPosition(entity.position))
        && (payload.nearbyBlocks === undefined
            || (Array.isArray(payload.nearbyBlocks) && payload.nearbyBlocks.every(isNearbyBlock)))
        && (payload.targetedBlock === undefined
            || payload.targetedBlock === null
            || isNearbyBlock(payload.targetedBlock))
}

export function isBridgeAckMessage(value: unknown): value is ReturnType<typeof createAckMessage> {
    if (!hasValidEnvelope(value, 'ack')) return false
    const payload = value.payload
    return typeof payload.commandId === 'string'
        && payload.commandId.trim().length > 0
        && (payload.status === 'ok' || payload.status === 'error')
        && (payload.detail === undefined || typeof payload.detail === 'string')
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
