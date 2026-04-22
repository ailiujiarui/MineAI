import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createAttackCommand,
  createEquipHotbarCommand,
  createLookCommand,
  createMoveCommand,
  createStopAllCommand,
  createUseSkillCommand,
  sendAttackCommand,
  sendEquipHotbarCommand,
  sendLookCommand,
  sendMoveCommand,
  sendUseSkillCommand,
  sendStopAllCommand
} from '../../src/clientBridge/clientCommander.js'

test('createLookCommand builds a single structured look action', () => {
  const command = createLookCommand('look-1', 180, -10)

  assert.equal(command.type, 'command')
  assert.equal(command.payload.actions.length, 1)
  assert.deepEqual(command.payload.actions[0], {
    kind: 'look',
    yaw: 180,
    pitch: -10
  })
})

test('createAttackCommand builds a tap attack against a target entity id', () => {
  const command = createAttackCommand('atk-1', 42)

  assert.deepEqual(command.payload.actions[0], {
    kind: 'attack',
    mode: 'tap',
    targetEntityId: 42
  })
})

test('createStopAllCommand builds a stop_all action', () => {
  const command = createStopAllCommand('stop-1')

  assert.deepEqual(command.payload.actions, [{ kind: 'stop_all' }])
})

test('createMoveCommand and createEquipHotbarCommand build structured movement actions', () => {
  const move = createMoveCommand('move-1', 1, -1, true, true)
  const equip = createEquipHotbarCommand('equip-1', 4)

  assert.deepEqual(move.payload.actions[0], {
    kind: 'move',
    forward: 1,
    strafe: -1,
    jump: true,
    sprint: true
  })
  assert.deepEqual(equip.payload.actions[0], {
    kind: 'equip_hotbar',
    hotbarIndex: 4
  })
})

test('createUseSkillCommand builds a structured skill action', () => {
  const command = createUseSkillCommand('skill-1', 'weapon_innate')

  assert.deepEqual(command.payload.actions[0], {
    kind: 'use_skill',
    skillSlot: 'weapon_innate'
  })
})

test('sendLookCommand delegates to the bridge server with a structured command', async () => {
  const calls: any[] = []
  const bridgeServer = {
    async sendCommand(clientId: string, command: any) {
      calls.push({ clientId, command })
    }
  }

  await sendLookCommand(bridgeServer as any, 'forge-agent-1', 'look-2', 15, 5)

  assert.equal(calls.length, 1)
  assert.equal(calls[0].clientId, 'forge-agent-1')
  assert.equal(calls[0].command.payload.actions[0].kind, 'look')
})

test('sendAttackCommand and sendStopAllCommand reuse the same bridge abstraction', async () => {
  const calls: any[] = []
  const bridgeServer = {
    async sendCommand(clientId: string, command: any) {
      calls.push({ clientId, command })
    }
  }

  await sendAttackCommand(bridgeServer as any, 'forge-agent-2', 'atk-2', 99)
  await sendStopAllCommand(bridgeServer as any, 'forge-agent-2', 'stop-2')

  assert.equal(calls.length, 2)
  assert.equal(calls[0].command.payload.actions[0].kind, 'attack')
  assert.equal(calls[1].command.payload.actions[0].kind, 'stop_all')
})

test('sendMoveCommand and sendEquipHotbarCommand delegate structured actions through the bridge', async () => {
  const calls: any[] = []
  const bridgeServer = {
    async sendCommand(clientId: string, command: any) {
      calls.push({ clientId, command })
    }
  }

  await sendMoveCommand(bridgeServer as any, 'forge-agent-3', 'move-2', 1, 0, false, true)
  await sendEquipHotbarCommand(bridgeServer as any, 'forge-agent-3', 'equip-2', 2)

  assert.equal(calls[0].command.payload.actions[0].kind, 'move')
  assert.equal(calls[1].command.payload.actions[0].kind, 'equip_hotbar')
})

test('sendUseSkillCommand delegates structured skill actions through the bridge', async () => {
  const calls: any[] = []
  const bridgeServer = {
    async sendCommand(clientId: string, command: any) {
      calls.push({ clientId, command })
    }
  }

  await sendUseSkillCommand(bridgeServer as any, 'forge-agent-4', 'skill-2', 'weapon_innate')

  assert.equal(calls[0].command.payload.actions[0].kind, 'use_skill')
  assert.equal(calls[0].command.payload.actions[0].skillSlot, 'weapon_innate')
})
