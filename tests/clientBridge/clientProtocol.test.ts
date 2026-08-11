import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createAckMessage,
  CLIENT_BRIDGE_PROTOCOL_VERSION,
  createCommandMessage,
  createHelloMessage,
  createSnapshotMessage,
  isBridgeAckMessage,
  isBridgeHelloMessage,
  isBridgeSnapshotMessage
} from '../../src/clientBridge/clientProtocol.js'

test('createHelloMessage advertises protocol version and runtime capabilities', () => {
  const message = createHelloMessage({
    clientId: 'forge-agent-1',
    minecraftVersion: '1.20.1',
    loader: 'forge',
    modCapabilities: {
      epicFight: true,
      slashBlade: true
    }
  })

  assert.equal(message.type, 'hello')
  assert.equal(message.protocolVersion, CLIENT_BRIDGE_PROTOCOL_VERSION)
  assert.equal(message.payload.loader, 'forge')
  assert.equal(message.payload.modCapabilities.epicFight, true)
})

test('createSnapshotMessage carries combat mode and player state', () => {
  const message = createSnapshotMessage({
    tick: 42,
    player: {
      name: 'BotPilot',
      health: 18,
      food: 20,
      position: { x: 1, y: 64, z: 2 },
      yaw: 90,
      pitch: 5,
      combatMode: 'epicfight'
    },
    nearbyEntities: [
      {
        entityId: 12,
        name: 'zombie',
        type: 'hostile',
        distance: 2.5,
        position: { x: 3, y: 64, z: 2 }
      }
    ],
    nearbyBlocks: [
      {
        name: 'oak_log',
        distance: 3,
        position: { x: 4, y: 64, z: 2 }
      }
    ],
    targetedBlock: {
      name: 'oak_log',
      distance: 3,
      position: { x: 4, y: 64, z: 2 }
    }
  })

  assert.equal(message.type, 'snapshot')
  assert.equal(message.payload.tick, 42)
  assert.equal(message.payload.player.combatMode, 'epicfight')
  assert.equal(message.payload.player.position.y, 64)
  assert.equal(message.payload.nearbyEntities[0].entityId, 12)
  assert.equal(message.payload.nearbyBlocks[0].name, 'oak_log')
  assert.equal(message.payload.targetedBlock?.position.x, 4)
})

test('createCommandMessage wraps structured client actions without string commands', () => {
  const message = createCommandMessage({
    id: 'cmd-1',
    actions: [
      { kind: 'look', yaw: 90, pitch: 10 },
      { kind: 'attack', mode: 'tap', targetEntityId: 12 },
      { kind: 'use_skill', skillSlot: 'weapon_innate' },
      { kind: 'mine_block', position: { x: 4, y: 64, z: 2 } }
    ]
  })

  assert.equal(message.type, 'command')
  assert.equal(message.payload.actions.length, 4)
  assert.equal(message.payload.actions[1].kind, 'attack')
  assert.equal(message.payload.actions[2].skillSlot, 'weapon_innate')
  assert.equal(message.payload.actions[3].kind, 'mine_block')
})

test('createAckMessage carries command completion status', () => {
  const message = createAckMessage({
    commandId: 'cmd-1',
    status: 'ok',
    detail: 'executed'
  })

  assert.equal(message.type, 'ack')
  assert.equal(message.protocolVersion, CLIENT_BRIDGE_PROTOCOL_VERSION)
  assert.equal(message.payload.commandId, 'cmd-1')
  assert.equal(message.payload.status, 'ok')
})

test('bridge message validators reject incompatible protocol versions and malformed payloads', () => {
  const hello = createHelloMessage({
    clientId: 'forge-agent-validated',
    minecraftVersion: '1.20.1',
    loader: 'forge',
    modCapabilities: { epicFight: false, slashBlade: false }
  })
  const snapshot = createSnapshotMessage({
    tick: 1,
    player: {
      name: 'BotPilot',
      health: 20,
      food: 20,
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      combatMode: 'vanilla'
    },
    nearbyEntities: []
  })

  assert.equal(isBridgeHelloMessage(hello), true)
  assert.equal(isBridgeHelloMessage({ ...hello, protocolVersion: 999 }), false)
  assert.equal(isBridgeSnapshotMessage(snapshot), true)
  assert.equal(isBridgeSnapshotMessage({ ...snapshot, payload: { tick: 1 } }), false)
  assert.equal(isBridgeAckMessage(createAckMessage({ commandId: 'cmd-1', status: 'ok' })), true)
  assert.equal(isBridgeAckMessage(createAckMessage({ commandId: '', status: 'ok' })), false)
})
