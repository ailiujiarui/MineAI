import test from 'node:test'
import assert from 'node:assert/strict'
import { AutonomyController, buildAutonomySnapshot } from '../../src/autonomy/controller.js'

test('controller issues a goal command on the first autonomous tick', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {},
        nearbyBlocks: ['oak_log'],
        hunger: 20,
        health: 20
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'gather_wood')
  assert.equal(commands.length, 2)
  assert.match(commands[0], /^!goal\("/)
  assert.equal(commands[1], '!collectBlocks("oak_log", 4)')
})

test('controller does not resend the same goal while stage is unchanged', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {},
        nearbyBlocks: ['oak_log'],
        hunger: 20,
        health: 20
      })
    }
  )

  await controller.tick()
  await controller.tick()

  assert.equal(commands.length, 2)
  assert.equal(commands[1], '!collectBlocks("oak_log", 4)')
})

test('controller stands down when an explicit task is active', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: { task_id: 'manual_task' } },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {},
        nearbyBlocks: ['oak_log'],
        hunger: 20,
        health: 20
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision, null)
  assert.equal(commands.length, 0)
})

test('controller stays idle in unrestricted creative mode without a player mission', async () => {
  const commands = []
  const decisions = []
  const controller = new AutonomyController(
    { task: { data: null }, history: { add: async () => {} } },
    {
      executeCommand: async (_, command) => commands.push(command),
      onDecision: async (decision) => decisions.push(decision),
      buildSnapshot: () => ({
        creativeUnrestricted: true,
        inventoryCounts: {},
        nearbyBlocks: ['oak_log'],
        hunger: 20,
        health: 20
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'creative_ready')
  assert.deepEqual(commands, [])
  assert.deepEqual(decisions, [])
})

test('controller accepts a player mission in unrestricted creative mode without survival commands', async () => {
  const commands = []
  const controller = new AutonomyController(
    { task: { data: null }, history: { add: async () => {} } },
    {
      executeCommand: async (_, command) => commands.push(command),
      buildSnapshot: () => ({
        creativeUnrestricted: true,
        inventoryCounts: {},
        nearbyBlocks: ['oak_log'],
        hunger: 20,
        health: 20
      })
    }
  )
  controller.setMission('build a shelter')

  await controller.tick()

  assert.equal(commands.length, 1)
  assert.match(commands[0], /^!goal\("/)
  assert.match(commands[0], /build a shelter/)
})

test('controller prioritizes recovery goals over normal progression', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {
          wooden_pickaxe: 1
        },
        nearbyBlocks: ['stone'],
        hunger: 6,
        health: 20,
        hostileCount: 0,
        dangerScore: 0
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'recover_food')
  assert.equal(commands.length, 1)
  assert.match(commands[0], /food/i)
})

test('controller emits both goal and next atomic command for furnace stage', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {
          cobblestone: 12,
          stone_pickaxe: 1
        },
        nearbyBlocks: ['coal_ore'],
        hunger: 20,
        health: 20,
        hostileCount: 0,
        dangerScore: 0
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'secure_furnace_and_light')
  assert.equal(commands.length, 2)
  assert.match(commands[0], /^!goal\("/)
  assert.equal(commands[1], '!craftRecipe("furnace", 1)')
})

test('controller includes user mission in emitted stage goal without replacing atomic planning', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {
          stone_pickaxe: 1,
          cobblestone: 12
        },
        nearbyBlocks: ['coal_ore'],
        hunger: 20,
        health: 20,
        hostileCount: 0,
        dangerScore: 0
      })
    }
  )

  controller.setMission('Get iron gear and stay safe')
  await controller.tick()

  assert.match(commands[0], /Get iron gear and stay safe/)
  assert.equal(commands[1], '!craftRecipe("furnace", 1)')
})

test('controller does not enqueue new atomic command while agent is busy', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} },
      isIdle: () => false
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
      },
      buildSnapshot: () => ({
        inventoryCounts: {
          furnace: 1,
          torch: 8,
          stone_pickaxe: 1
        },
        nearbyBlocks: ['iron_ore'],
        hunger: 20,
        health: 20,
        hostileCount: 0,
        dangerScore: 0
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'reach_iron')
  assert.equal(commands.length, 0)
})

test('controller blacklists failed mining target and replans to the next one', async () => {
  const commands = []
  const snapshots = [
    {
      inventoryCounts: {
        furnace: 1,
        torch: 8,
        stone_pickaxe: 1
      },
      nearbyBlocks: [],
      knownResourceLocations: [
        { blockName: 'iron_ore', x: 2, y: 64, z: 0, exposedFaces: 4, distanceSq: 4 },
        { blockName: 'iron_ore', x: 8, y: 64, z: 0, exposedFaces: 2, distanceSq: 64 }
      ],
      position: { x: 0, y: 64, z: 0 },
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0
    },
    {
      inventoryCounts: {
        furnace: 1,
        torch: 8,
        stone_pickaxe: 1
      },
      nearbyBlocks: [],
      knownResourceLocations: [
        { blockName: 'iron_ore', x: 2, y: 64, z: 0, exposedFaces: 4, distanceSq: 4 },
        { blockName: 'iron_ore', x: 8, y: 64, z: 0, exposedFaces: 2, distanceSq: 64 }
      ],
      position: { x: 0, y: 64, z: 0 },
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0
    }
  ]
  let tickIndex = 0
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
        if (command.startsWith('!collectBlocks("iron_ore"')) {
          return 'Could not find any iron_ore in 96 blocks.'
        }
        return 'ok'
      },
      buildSnapshot: () => snapshots[Math.min(tickIndex++, snapshots.length - 1)]
    }
  )

  await controller.tick()
  await controller.tick()

  const ironCommands = commands.filter(command => command.startsWith('!collectBlocks("iron_ore"'))
  assert.equal(ironCommands.length, 2)
})

test('controller maps maid follow work into a followPlayer command during steady state', async () => {
  const commands = []
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
        return 'ok'
      },
      buildSnapshot: () => ({
        inventoryCounts: {
          stone_pickaxe: 1,
          iron_pickaxe: 1,
          shield: 1,
          furnace: 1,
          torch: 16
        },
        nearbyBlocks: [],
        hunger: 20,
        health: 20,
        hostileCount: 0,
        dangerScore: 0,
        maid: {
          modes: ['follow', 'patrol'],
          snapshot: {
            readyCrops: 0,
            emptyFarmland: 0,
            hostileCount: 0,
            inventoryUtilization: 0.3,
            playerDistance: 14
          },
          nearestPlayerName: 'pih'
        }
      })
    }
  )

  const decision = await controller.tick()

  assert.equal(decision.stage, 'survival_steady_state')
  assert.equal(commands.length, 2)
  assert.match(commands[0], /^!goal\("/)
  assert.equal(commands[1], '!followPlayer("pih", 4)')
})

test('buildAutonomySnapshot derives maid work context from players and inventory usage', () => {
  const botPosition = {
    x: 0,
    y: 64,
    z: 0,
    distanceSquared(other) {
      const dx = this.x - other.x
      const dy = this.y - other.y
      const dz = this.z - other.z
      return dx * dx + dy * dy + dz * dz
    },
    distanceTo(other) {
      return Math.sqrt(this.distanceSquared(other))
    }
  }
  const snapshot = buildAutonomySnapshot({
    bot: {
      entity: {
        position: botPosition
      },
      health: 20,
      food: 18,
      findBlocks: ({ matching }) => {
        const blocks = [
          { x: 2, y: 63, z: 1, name: 'wheat' },
          { x: 4, y: 63, z: 1, name: 'farmland' }
        ]
        return blocks
          .filter(block => matching({ name: block.name }))
          .map(block => ({
            x: block.x,
            y: block.y,
            z: block.z,
            offset(dx, dy, dz) {
              return { x: this.x + dx, y: this.y + dy, z: this.z + dz }
            }
          }))
      },
      blockAt: (pos) => {
        const key = `${pos.x},${pos.y},${pos.z}`
        const map = {
          '2,63,1': { name: 'wheat', _properties: { age: 7 }, position: pos },
          '2,64,1': { name: 'air', position: pos },
          '4,63,1': { name: 'farmland', position: pos },
          '4,64,1': { name: 'air', position: pos }
        }
        return map[key] || null
      },
      entities: {
        player_1: {
          type: 'player',
          username: 'pih',
          name: 'player',
          position: {
            x: 12,
            y: 64,
            z: 0,
            distanceTo(other) {
              const dx = this.x - other.x
              const dy = this.y - other.y
              const dz = this.z - other.z
              return Math.sqrt(dx * dx + dy * dy + dz * dz)
            }
          }
        }
      },
      inventory: {
        items: () => Array.from({ length: 20 }, (_, index) => ({ name: `item_${index}`, count: 1 })),
        slots: new Array(46)
      }
    }
  })

  assert.ok(snapshot.maid)
  assert.equal(snapshot.maid.nearestPlayerName, 'pih')
  assert.equal(snapshot.maid.snapshot.playerDistance, 12)
  assert.ok(snapshot.maid.snapshot.inventoryUtilization > 0.4)
  assert.equal(snapshot.maid.snapshot.readyCrops, 1)
  assert.equal(snapshot.maid.snapshot.emptyFarmland, 1)
  assert.equal(snapshot.maid.cropTarget, 'wheat')
  assert.ok(snapshot.maid.modes.includes('follow'))
})

test('controller sequences maid farm workflow from harvest to storage to replant to follow', async () => {
  const commands = []
  const snapshots = [
    {
      inventoryCounts: {
        stone_pickaxe: 1,
        iron_pickaxe: 1,
        shield: 1,
        furnace: 1,
        torch: 16
      },
      nearbyBlocks: [],
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0,
      maid: {
        modes: ['harvest', 'replant', 'store-items', 'follow', 'patrol'],
        snapshot: {
          readyCrops: 3,
          emptyFarmland: 0,
          hostileCount: 0,
          inventoryUtilization: 0.3,
          playerDistance: 12
        },
        cropTarget: 'wheat',
        nearestPlayerName: 'pih',
        hasNearbyChest: true
      }
    },
    {
      inventoryCounts: {
        stone_pickaxe: 1,
        iron_pickaxe: 1,
        shield: 1,
        furnace: 1,
        torch: 16,
        wheat: 12
      },
      nearbyBlocks: [],
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0,
      maid: {
        modes: ['harvest', 'replant', 'store-items', 'follow', 'patrol'],
        snapshot: {
          readyCrops: 0,
          emptyFarmland: 2,
          hostileCount: 0,
          inventoryUtilization: 0.5,
          playerDistance: 12
        },
        cropTarget: 'wheat',
        storeItemName: 'wheat',
        hasNearbyChest: true,
        replantTarget: {
          x: 8,
          y: 63,
          z: 2,
          seedType: 'wheat_seeds'
        },
        nearestPlayerName: 'pih'
      }
    },
    {
      inventoryCounts: {
        stone_pickaxe: 1,
        iron_pickaxe: 1,
        shield: 1,
        furnace: 1,
        torch: 16
      },
      nearbyBlocks: [],
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0,
      maid: {
        modes: ['harvest', 'replant', 'store-items', 'follow', 'patrol'],
        snapshot: {
          readyCrops: 0,
          emptyFarmland: 2,
          hostileCount: 0,
          inventoryUtilization: 0.3,
          playerDistance: 12
        },
        cropTarget: 'wheat',
        hasNearbyChest: true,
        replantTarget: {
          x: 8,
          y: 63,
          z: 2,
          seedType: 'wheat_seeds'
        },
        nearestPlayerName: 'pih'
      }
    },
    {
      inventoryCounts: {
        stone_pickaxe: 1,
        iron_pickaxe: 1,
        shield: 1,
        furnace: 1,
        torch: 16
      },
      nearbyBlocks: [],
      hunger: 20,
      health: 20,
      hostileCount: 0,
      dangerScore: 0,
      maid: {
        modes: ['harvest', 'replant', 'store-items', 'follow', 'patrol'],
        snapshot: {
          readyCrops: 0,
          emptyFarmland: 0,
          hostileCount: 0,
          inventoryUtilization: 0.2,
          playerDistance: 12
        },
        nearestPlayerName: 'pih',
        hasNearbyChest: true
      }
    }
  ]
  let tickIndex = 0
  const controller = new AutonomyController(
    {
      task: { data: null },
      history: { add: async () => {} }
    },
    {
      executeCommand: async (_, command) => {
        commands.push(command)
        return 'ok'
      },
      buildSnapshot: () => snapshots[Math.min(tickIndex++, snapshots.length - 1)]
    }
  )

  await controller.tick()
  await controller.tick()
  await controller.tick()
  await controller.tick()

  assert.equal(commands[1], '!collectBlocks("wheat", 3)')
  assert.equal(commands[2], '!putInChest("wheat", 64)')
  assert.equal(commands[3], '!tillAndSow(8, 63, 2, "wheat_seeds")')
  assert.equal(commands[4], '!followPlayer("pih", 4)')
})
