import type { BridgeCommandAction, BridgeCommandPayload, BridgeSnapshotPayload } from './clientProtocol.js'

function degrees(value: number) {
    return value * (180 / Math.PI)
}

function normalizeYaw(yaw: number) {
    let normalized = yaw % 360
    if (normalized > 180) normalized -= 360
    if (normalized < -180) normalized += 360
    return normalized
}

function computeLookAngles(
    from: { x: number; y: number; z: number },
    to: { x: number; y: number; z: number }
) {
    const dx = to.x - from.x
    const dy = to.y - from.y
    const dz = to.z - from.z
    const horizontal = Math.sqrt(dx * dx + dz * dz) || 0.0001

    const yaw = normalizeYaw(degrees(Math.atan2(dz, dx)) - 90)
    const pitch = -degrees(Math.atan2(dy, horizontal))

    return { yaw, pitch }
}

export function planBridgeCombatActions(
    snapshot: BridgeSnapshotPayload,
    options: { attackRange?: number } = {}
): BridgeCommandPayload {
    const attackRange = options.attackRange ?? 3.1
    const target = [...(snapshot.nearbyEntities || [])]
        .filter((entity) => entity.type === 'hostile')
        .sort((left, right) => left.distance - right.distance)[0]

    if (!target) {
        return {
            id: `combat-${snapshot.tick}`,
            actions: [{ kind: 'stop_all' }]
        }
    }

    const look = computeLookAngles(snapshot.player.position, target.position)
    const actions: BridgeCommandAction[] = [
        { kind: 'look', yaw: look.yaw, pitch: look.pitch }
    ]

    if (target.distance <= attackRange) {
        actions.push({ kind: 'attack', mode: 'tap', targetEntityId: target.entityId })
    } else {
        actions.push({
            kind: 'move',
            forward: 1,
            strafe: 0,
            jump: false,
            sprint: target.distance > 4
        })
    }

    return {
        id: `combat-${snapshot.tick}`,
        actions
    }
}
