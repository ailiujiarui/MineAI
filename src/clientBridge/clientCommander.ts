import { createCommandMessage } from './clientProtocol.js'

export function createLookCommand(id: string, yaw: number, pitch: number) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'look', yaw, pitch }
        ]
    })
}

export function createAttackCommand(id: string, targetEntityId?: number) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'attack', mode: 'tap', targetEntityId }
        ]
    })
}

export function createMoveCommand(id: string, forward: number, strafe: number, jump = false, sprint = false) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'move', forward, strafe, jump, sprint }
        ]
    })
}

export function createEquipHotbarCommand(id: string, hotbarIndex: number) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'equip_hotbar', hotbarIndex }
        ]
    })
}

export function createUseSkillCommand(id: string, skillSlot: string) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'use_skill', skillSlot }
        ]
    })
}

export function createStopAllCommand(id: string) {
    return createCommandMessage({
        id,
        actions: [
            { kind: 'stop_all' }
        ]
    })
}

export async function sendLookCommand(bridgeServer: any, clientId: string, id: string, yaw: number, pitch: number) {
    await bridgeServer.sendCommand(clientId, createLookCommand(id, yaw, pitch))
}

export async function sendAttackCommand(bridgeServer: any, clientId: string, id: string, targetEntityId?: number) {
    await bridgeServer.sendCommand(clientId, createAttackCommand(id, targetEntityId))
}

export async function sendMoveCommand(
    bridgeServer: any,
    clientId: string,
    id: string,
    forward: number,
    strafe: number,
    jump = false,
    sprint = false
) {
    await bridgeServer.sendCommand(clientId, createMoveCommand(id, forward, strafe, jump, sprint))
}

export async function sendEquipHotbarCommand(bridgeServer: any, clientId: string, id: string, hotbarIndex: number) {
    await bridgeServer.sendCommand(clientId, createEquipHotbarCommand(id, hotbarIndex))
}

export async function sendUseSkillCommand(bridgeServer: any, clientId: string, id: string, skillSlot: string) {
    await bridgeServer.sendCommand(clientId, createUseSkillCommand(id, skillSlot))
}

export async function sendStopAllCommand(bridgeServer: any, clientId: string, id: string) {
    await bridgeServer.sendCommand(clientId, createStopAllCommand(id))
}
