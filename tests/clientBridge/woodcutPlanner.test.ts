import test from 'node:test'
import assert from 'node:assert/strict'

import { planWoodcutActions } from '../../src/clientBridge/woodcutPlanner.js'

test('plans mining when a targeted log block is already in view and in range', () => {
  const plan = planWoodcutActions({
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
    nearbyEntities: [],
    nearbyBlocks: [
      { name: 'oak_log', distance: 2.6, position: { x: 2, y: 64, z: 0 } }
    ],
    targetedBlock: { name: 'oak_log', distance: 2.6, position: { x: 2, y: 64, z: 0 } }
  })

  assert.equal(plan.actions[0].kind, 'mine_block')
  assert.deepEqual(plan.actions[0].position, { x: 2, y: 64, z: 0 })
})

test('plans look and move toward nearest log block when no targeted block is available', () => {
  const plan = planWoodcutActions({
    tick: 2,
    player: {
      name: 'BotPilot',
      health: 20,
      food: 20,
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      combatMode: 'vanilla'
    },
    nearbyEntities: [],
    nearbyBlocks: [
      { name: 'oak_log', distance: 5.5, position: { x: 5, y: 64, z: 1 } }
    ],
    targetedBlock: null
  })

  assert.equal(plan.actions[0].kind, 'look')
  assert.equal(plan.actions[1].kind, 'move')
})

test('returns stop_all when no log-like blocks are visible', () => {
  const plan = planWoodcutActions({
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
    nearbyEntities: [],
    nearbyBlocks: [],
    targetedBlock: null
  })

  assert.deepEqual(plan.actions, [{ kind: 'stop_all' }])
})

test('does not mine a non-log targeted block and chooses the nearest valid log', () => {
  const plan = planWoodcutActions({
    tick: 4,
    player: {
      name: 'BotPilot', health: 20, food: 20,
      position: { x: 0, y: 64, z: 0 }, yaw: 0, pitch: 0, combatMode: 'vanilla'
    },
    nearbyEntities: [],
    nearbyBlocks: [
      { name: 'dirt', distance: 1, position: { x: 1, y: 64, z: 0 } },
      { name: 'birch_log', distance: 6, position: { x: 6, y: 64, z: 0 } },
      { name: 'spruce_log', distance: 3, position: { x: 3, y: 64, z: 0 } }
    ],
    targetedBlock: { name: 'dirt', distance: 1, position: { x: 1, y: 64, z: 0 } }
  })

  assert.equal(plan.actions[0].kind, 'look')
  assert.equal(plan.actions[1].kind, 'move')
  assert.equal(plan.actions[1].sprint, false)
})

test('moves toward an out-of-range targeted log until it can be mined', () => {
  const plan = planWoodcutActions({
    tick: 5,
    player: {
      name: 'BotPilot', health: 20, food: 20,
      position: { x: 0, y: 64, z: 0 }, yaw: 0, pitch: 0, combatMode: 'vanilla'
    },
    nearbyEntities: [],
    nearbyBlocks: [{ name: 'minecraft:crimson_stem', distance: 8, position: { x: 8, y: 64, z: 0 } }],
    targetedBlock: { name: 'minecraft:crimson_stem', distance: 8, position: { x: 8, y: 64, z: 0 } }
  })

  assert.equal(plan.actions[0].kind, 'look')
  assert.deepEqual(plan.actions[1], { kind: 'move', forward: 1, strafe: 0, jump: false, sprint: true })
})
