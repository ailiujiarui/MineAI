import test from 'node:test'
import assert from 'node:assert/strict'
import { planAutonomyCommand } from '../../src/autonomy/commandPlanner.js'

test('does not issue survival resource commands in unrestricted creative mode', () => {
  assert.equal(planAutonomyCommand('creative_ready', { creativeUnrestricted: true }), null)
})

test('plans nearby log collection for gather_wood stage', () => {
  const command = planAutonomyCommand('gather_wood', {
    inventoryCounts: {},
    nearbyBlocks: ['spruce_log', 'dirt']
  })

  assert.equal(command, '!collectBlocks("spruce_log", 4)')
})

test('plans exploration for logs when no logs are nearby during gather_wood', () => {
  const command = planAutonomyCommand('gather_wood', {
    inventoryCounts: {},
    nearbyBlocks: ['dirt', 'stone']
  })

  assert.equal(command, '!exploreForBlock("oak_log", 64, 24)')
})

test('uses known resource locations to prefer the best nearby log target', () => {
  const command = planAutonomyCommand('gather_wood', {
    inventoryCounts: {},
    nearbyBlocks: [],
    knownResourceLocations: [
      { blockName: 'birch_log', x: 12, y: 64, z: 0, exposedFaces: 1, distanceSq: 144 },
      { blockName: 'spruce_log', x: 4, y: 64, z: 0, exposedFaces: 3, distanceSq: 16 }
    ]
  })

  assert.equal(command, '!collectBlocks("spruce_log", 4)')
})

test('plans wooden pickaxe crafting after planks and sticks are ready', () => {
  const command = planAutonomyCommand('craft_wooden_pickaxe', {
    inventoryCounts: {
      spruce_planks: 8,
      stick: 4
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("wooden_pickaxe", 1)')
})

test('plans stick crafting before wooden pickaxe when sticks are missing', () => {
  const command = planAutonomyCommand('craft_wooden_pickaxe', {
    inventoryCounts: {
      spruce_planks: 8
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("stick", 1)')
})

test('plans stone collection for upgrade_to_stone when stone is nearby', () => {
  const command = planAutonomyCommand('upgrade_to_stone', {
    inventoryCounts: {
      wooden_pickaxe: 1
    },
    nearbyBlocks: ['stone']
  })

  assert.equal(command, '!collectBlocks("stone", 3)')
})

test('plans stone pickaxe crafting after stone and sticks are ready', () => {
  const command = planAutonomyCommand('upgrade_to_stone', {
    inventoryCounts: {
      wooden_pickaxe: 1,
      stick: 2,
      cobblestone: 3,
      crafting_table: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("stone_pickaxe", 1)')
})

test('plans furnace crafting when cobblestone is ready but furnace is missing', () => {
  const command = planAutonomyCommand('secure_furnace_and_light', {
    inventoryCounts: {
      cobblestone: 12,
      stone_pickaxe: 1
    },
    nearbyBlocks: ['coal_ore']
  })

  assert.equal(command, '!craftRecipe("furnace", 1)')
})

test('plans coal collection when furnace stage lacks fuel', () => {
  const command = planAutonomyCommand('secure_furnace_and_light', {
    inventoryCounts: {
      furnace: 1,
      stone_pickaxe: 1
    },
    nearbyBlocks: ['coal_ore']
  })

  assert.equal(command, '!collectBlocks("coal_ore", 3)')
})

test('plans stick crafting before torches when sticks are missing', () => {
  const command = planAutonomyCommand('secure_furnace_and_light', {
    inventoryCounts: {
      furnace: 1,
      coal: 3,
      spruce_planks: 4,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("stick", 1)')
})

test('plans plank crafting before sticks when only logs remain', () => {
  const command = planAutonomyCommand('secure_furnace_and_light', {
    inventoryCounts: {
      furnace: 1,
      coal: 3,
      spruce_log: 2,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("spruce_planks", 1)')
})

test('plans torch crafting when fuel exists but torches are missing', () => {
  const command = planAutonomyCommand('secure_furnace_and_light', {
    inventoryCounts: {
      furnace: 1,
      coal: 3,
      stick: 2,
      spruce_planks: 2,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("torch", 2)')
})

test('plans iron mining when iron ore is nearby', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      stone_pickaxe: 1
    },
    nearbyBlocks: ['iron_ore']
  })

  assert.equal(command, '!collectBlocks("iron_ore", 3)')
})

test('uses known resource locations to prefer the best iron target', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      stone_pickaxe: 1
    },
    nearbyBlocks: [],
    knownResourceLocations: [
      { blockName: 'deepslate_iron_ore', x: 20, y: 20, z: 0, exposedFaces: 1, distanceSq: 2000 },
      { blockName: 'iron_ore', x: 6, y: 62, z: 0, exposedFaces: 3, distanceSq: 36 }
    ]
  })

  assert.equal(command, '!collectBlocks("iron_ore", 3)')
})

test('plans iron smelting when ore is already in inventory', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      coal: 4,
      iron_ore: 3,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!smeltItem("iron_ore", 3)')
})

test('plans iron smelting when raw iron is already in inventory', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      coal: 4,
      raw_iron: 4,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!smeltItem("raw_iron", 4)')
})

test('plans fuel acquisition before smelting iron ore when coal is missing', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      iron_ore: 3,
      stone_pickaxe: 1
    },
    nearbyBlocks: ['coal_ore']
  })

  assert.equal(command, '!collectBlocks("coal_ore", 3)')
})

test('plans shield crafting before iron pickaxe when ingots are available', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      iron_ingot: 4,
      spruce_planks: 6,
      stick: 2,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("shield", 1)')
})

test('plans iron pickaxe crafting after shield is secured', () => {
  const command = planAutonomyCommand('reach_iron', {
    inventoryCounts: {
      furnace: 1,
      torch: 8,
      iron_ingot: 3,
      shield: 1,
      stick: 2,
      stone_pickaxe: 1
    },
    nearbyBlocks: []
  })

  assert.equal(command, '!craftRecipe("iron_pickaxe", 1)')
})
