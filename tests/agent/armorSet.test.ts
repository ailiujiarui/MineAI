import test from 'node:test'
import assert from 'node:assert/strict'

import { giveArmorSet, resolveArmorSetItems } from '../../src/agent/library/armorSet.js'

function createBot({ creative = false, items = [], emptySlots = 8 } = {}) {
  const slots = Array(45).fill(null)
  items.forEach((name, index) => {
    slots[9 + index] = { name, count: 1 }
  })
  for (let index = 9 + items.length + emptySlots; index < 45; index += 1) {
    slots[index] = { name: `filler_${index}`, count: 1 }
  }
  return {
    game: { gameMode: creative ? 'creative' : 'survival' },
    restrict_to_inventory: false,
    players: { pih: { entity: {} } },
    inventory: { slots, inventoryStart: 9, inventoryEnd: 45 }
  }
}

test('resolves a bounded complete armor set without tools or weapons', () => {
  assert.deepEqual(resolveArmorSetItems('netherite'), [
    'netherite_helmet',
    'netherite_chestplate',
    'netherite_leggings',
    'netherite_boots'
  ])
  assert.equal(resolveArmorSetItems('wood'), null)
})

test('gives all four physical armor pieces serially', async () => {
  const items = resolveArmorSetItems('netherite')
  const bot = createBot({ items })
  const given = []

  const result = await giveArmorSet(bot, 'pih', 'netherite', {
    giveItem: async (_bot, itemName) => {
      given.push(itemName)
      return true
    }
  })

  assert.equal(result, true)
  assert.deepEqual(given, items)
})

test('materializes missing creative pieces before transfer', async () => {
  const bot = createBot({ creative: true, items: ['diamond_chestplate'] })
  const materialized = []
  const given = []

  const result = await giveArmorSet(bot, 'pih', 'diamond', {
    materialize: async (_bot, itemName) => {
      materialized.push(itemName)
      bot.inventory.slots[9 + materialized.length] = { name: itemName, count: 1 }
      return { ok: true }
    },
    giveItem: async (_bot, itemName) => {
      given.push(itemName)
      return true
    }
  })

  assert.equal(result, true)
  assert.deepEqual(materialized, [
    'diamond_helmet',
    'diamond_leggings',
    'diamond_boots'
  ])
  assert.equal(given.length, 4)
})

test('preflight prevents a partial transfer when pieces are missing', async () => {
  const bot = createBot({ creative: false, items: ['iron_helmet'] })
  let giveCalls = 0
  const logs = []

  const result = await giveArmorSet(bot, 'pih', 'iron', {
    giveItem: async () => {
      giveCalls += 1
      return true
    },
    log: (message) => logs.push(message)
  })

  assert.equal(result, false)
  assert.equal(giveCalls, 0)
  assert.match(logs.join('\n'), /Missing armor pieces/)
})

test('reports completed and remaining pieces after a transfer failure', async () => {
  const bot = createBot({ items: resolveArmorSetItems('golden') })
  const logs = []
  let calls = 0

  const result = await giveArmorSet(bot, 'pih', 'golden', {
    giveItem: async () => {
      calls += 1
      return calls < 3
    },
    log: (message) => logs.push(message)
  })

  assert.equal(result, false)
  assert.match(logs.join('\n'), /Completed: golden_helmet, golden_chestplate/)
  assert.match(logs.join('\n'), /Remaining: golden_leggings, golden_boots/)
})

