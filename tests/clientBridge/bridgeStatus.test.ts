import test from 'node:test'
import assert from 'node:assert/strict'

import { formatBridgeClientSummary } from '../../src/clientBridge/bridgeStatus.js'

test('formatBridgeClientSummary shows combat mode, entity count, and latest ack status', () => {
  const summary = formatBridgeClientSummary({
    clientId: 'forge-agent-1',
    connectedAt: 1710000000000,
    hello: {
      clientId: 'forge-agent-1',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: true,
        slashBlade: true
      }
    },
    snapshot: {
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
      ]
    },
    lastAck: {
      commandId: 'cmd-1',
      status: 'ok',
      detail: 'accepted'
    }
  })

  assert.match(summary, /forge-agent-1/)
  assert.match(summary, /epicfight/)
  assert.match(summary, /entities=1/)
  assert.match(summary, /ack=ok/)
})
