import test from 'node:test'
import assert from 'node:assert/strict'

import { createBridgeCombatRuntime } from '../../src/clientBridge/bridgeCombatRuntime.js'

test('bridge combat runtime attacks hostile targets already in range', async () => {
  const calls: any[] = []
  const runtime = createBridgeCombatRuntime({
    bridgeServer: {
      listClients() {
        return [{
          id: 'forge-agent-1',
          snapshot: {
            payload: {
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
              nearbyEntities: [
                {
                  entityId: 12,
                  name: 'zombie',
                  type: 'hostile',
                  distance: 2.4,
                  position: { x: 2, y: 64, z: 0 }
                }
              ]
            }
          }
        }]
      },
      async sendCommand(clientId: string, command: any) {
        calls.push({ clientId, command })
      }
    } as any
  })

  await runtime.tick()

  assert.equal(calls.length, 1)
  assert.equal(calls[0].command.payload.actions[1].kind, 'attack')
})

test('bridge combat runtime stops clients with no hostile targets', async () => {
  const calls: any[] = []
  const runtime = createBridgeCombatRuntime({
    bridgeServer: {
      listClients() {
        return [{
          id: 'forge-agent-2',
          snapshot: {
            payload: {
              tick: 2,
              player: {
                name: 'BotPilot',
                health: 20,
                food: 20,
                position: { x: 0, y: 64, z: 0 },
                yaw: 0,
                pitch: 0,
                combatMode: 'epicfight'
              },
              nearbyEntities: []
            }
          }
        }]
      },
      async sendCommand(clientId: string, command: any) {
        calls.push({ clientId, command })
      }
    } as any
  })

  await runtime.tick()

  assert.equal(calls.length, 1)
  assert.equal(calls[0].command.payload.actions[0].kind, 'stop_all')
})
