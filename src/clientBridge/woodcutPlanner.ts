import type { BridgeCommandPayload, BridgeSnapshotPayload } from './clientProtocol.js'

type WoodcutSnapshot = BridgeSnapshotPayload & {
    nearbyBlocks?: Array<{ name: string; distance: number; position: { x: number; y: number; z: number } }>
    targetedBlock?: { name: string; distance: number; position: { x: number; y: number; z: number } } | null
}

function lookAngles(from: { x: number; y: number; z: number }, to: { x: number; y: number; z: number }) {
    const dx = to.x - from.x
    const dy = to.y - from.y
    const dz = to.z - from.z
    const horizontal = Math.sqrt(dx * dx + dz * dz) || 0.0001
    return {
        yaw: Math.atan2(dz, dx) * 180 / Math.PI - 90,
        pitch: -Math.atan2(dy, horizontal) * 180 / Math.PI
    }
}

export function planWoodcutActions(snapshot: WoodcutSnapshot): BridgeCommandPayload {
    const miningRange = 4.5
    const isWoodBlock = (name: string) => {
        const normalized = name.toLowerCase().replace(/^minecraft:/, '')
        return normalized.endsWith('_log') || normalized.endsWith('_wood')
            || normalized.endsWith('_stem') || normalized.endsWith('_hyphae')
    }
    const target = snapshot.targetedBlock && isWoodBlock(snapshot.targetedBlock.name)
        ? snapshot.targetedBlock
        : (snapshot.nearbyBlocks || [])
            .filter((block) => isWoodBlock(block.name))
            .sort((left, right) => left.distance - right.distance)[0]

    if (!target) {
        return { id: `woodcut-${snapshot.tick}`, actions: [{ kind: 'stop_all' }] }
    }

    if (snapshot.targetedBlock === target && target.distance >= 0 && target.distance <= miningRange) {
        return {
            id: `woodcut-${snapshot.tick}`,
            actions: [{ kind: 'mine_block', position: target.position }]
        }
    }

    const look = lookAngles(snapshot.player.position, target.position)
    return {
        id: `woodcut-${snapshot.tick}`,
        actions: [
            { kind: 'look', yaw: look.yaw, pitch: look.pitch },
            { kind: 'move', forward: 1, strafe: 0, jump: false, sprint: target.distance > 6 }
        ]
    }
}
