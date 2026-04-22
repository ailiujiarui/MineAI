import test from 'node:test'
import assert from 'node:assert/strict'

import { planBridgeCombatActions } from '../../src/clientBridge/combatPlanner.js'

test('plans look and attack against the nearest hostile in range', () => {
  const plan = planBridgeCombatActions({
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
        entityId: 42,
        name: 'zombie',
        type: 'hostile',
        distance: 2.2,
        position: { x: 2, y: 64, z: 0 }
      }
    ]
  }, { attackRange: 3.1 })

  assert.equal(plan.actions[0].kind, 'look')
  assert.equal(plan.actions[1].kind, 'attack')
  assert.equal(plan.actions[1].targetEntityId, 42)
})

test('plans sprint movement toward a hostile outside attack range', () => {
  const plan = planBridgeCombatActions({
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
    nearbyEntities: [
      {
        entityId: 7,
        name: 'skeleton',
        type: 'hostile',
        distance: 7.5,
        position: { x: 6, y: 64, z: 4 }
      }
    ]
  }, { attackRange: 3.1 })

  assert.equal(plan.actions[0].kind, 'look')
  assert.deepEqual(plan.actions[1], {
    kind: 'move',
    forward: 1,
    strafe: 0,
    jump: false,
    sprint: true
  })
})

test('plans stop_all when no hostile target is visible', () => {
  const plan = planBridgeCombatActions({
    tick: 3,
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

  assert.deepEqual(plan.actions, [{ kind: 'stop_all' }])
})
