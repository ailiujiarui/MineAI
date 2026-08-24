import test from 'node:test'
import assert from 'node:assert/strict'
import { decideAutonomyStage } from '../../src/autonomy/progression.js'

test('chooses a non-destructive ready stage in unrestricted creative mode', () => {
  const decision = decideAutonomyStage({ creativeUnrestricted: true, inventoryCounts: {}, nearbyBlocks: [] })
  assert.equal(decision.stage, 'creative_ready')
  assert.match(decision.goalPrompt, /!creativeItem/)
  assert.doesNotMatch(decision.goalPrompt, /!collectBlocks/)
})

test('chooses gather_wood when starting empty handed', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {},
    nearbyBlocks: ['oak_log', 'dirt'],
    hunger: 20,
    health: 20
  })

  assert.equal(decision.stage, 'gather_wood')
  assert.match(decision.goalPrompt, /collect logs/i)
  assert.match(decision.goalPrompt, /!nearbyBlocks/)
  assert.match(decision.goalPrompt, /!collectBlocks\("oak_log"/)
})

test('chooses craft_wooden_pickaxe when logs and planks exist but no wooden pickaxe', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {
      oak_log: 3,
      oak_planks: 8,
      stick: 4
    },
    nearbyBlocks: ['grass_block'],
    hunger: 20,
    health: 20
  })

  assert.equal(decision.stage, 'craft_wooden_pickaxe')
  assert.match(decision.goalPrompt, /wooden pickaxe/i)
  assert.match(decision.goalPrompt, /!inventory/)
  assert.match(decision.goalPrompt, /!craftRecipe\("wooden_pickaxe"/)
})

test('chooses upgrade_to_stone when wooden pickaxe exists but stone pickaxe does not', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {
      wooden_pickaxe: 1,
      stick: 2,
      oak_planks: 8
    },
    nearbyBlocks: ['stone', 'coal_ore'],
    hunger: 20,
    health: 20
  })

  assert.equal(decision.stage, 'upgrade_to_stone')
  assert.match(decision.goalPrompt, /stone pickaxe/i)
  assert.match(decision.goalPrompt, /!searchForBlock\("stone"/)
  assert.match(decision.goalPrompt, /!craftRecipe\("stone_pickaxe"/)
})

test('chooses secure_furnace_and_light after stone tools but before furnace and torches', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {
      stone_pickaxe: 1,
      cobblestone: 12
    },
    nearbyBlocks: ['coal_ore'],
    hunger: 20,
    health: 20
  })

  assert.equal(decision.stage, 'secure_furnace_and_light')
  assert.match(decision.goalPrompt, /furnace/i)
  assert.match(decision.goalPrompt, /torches/i)
  assert.match(decision.goalPrompt, /!craftRecipe\("furnace"/)
  assert.match(decision.goalPrompt, /!craftRecipe\("torch"/)
})

test('chooses reach_iron after furnace setup but before shield and iron pickaxe', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {
      stone_pickaxe: 1,
      furnace: 1,
      torch: 12,
      coal: 6
    },
    nearbyBlocks: ['iron_ore'],
    hunger: 18,
    health: 20
  })

  assert.equal(decision.stage, 'reach_iron')
  assert.match(decision.goalPrompt, /iron pickaxe/i)
  assert.match(decision.goalPrompt, /shield/i)
  assert.match(decision.goalPrompt, /!smeltItem\("iron_ore"/)
  assert.match(decision.goalPrompt, /!craftRecipe\("shield"/)
})

test('chooses survival_steady_state after iron pickaxe and shield are ready', () => {
  const decision = decideAutonomyStage({
    inventoryCounts: {
      iron_pickaxe: 1,
      shield: 1,
      torch: 8
    },
    nearbyBlocks: [],
    hunger: 16,
    health: 20
  })

  assert.equal(decision.stage, 'survival_steady_state')
  assert.match(decision.goalPrompt, /survive/i)
  assert.match(decision.goalPrompt, /!stats/)
  assert.match(decision.goalPrompt, /!inventory/)
})
