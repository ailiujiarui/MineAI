import test from 'node:test'
import assert from 'node:assert/strict'
import { exploreForBlock } from '../../src/agent/library/skills.js'

test('exploreForBlock retries search after moving away when first search fails', async () => {
  const calls = []
  let searchCount = 0
  const result = await exploreForBlock(
    {},
    'oak_log',
    64,
    24,
    3,
    {
      goToNearestBlock: async (_bot, blockType, minDistance, range) => {
        calls.push(['search', blockType, minDistance, range])
        searchCount += 1
        return searchCount >= 3
      },
      moveAway: async (_bot, distance) => {
        calls.push(['move', distance])
        return true
      },
      log: () => {}
    }
  )

  assert.equal(result, true)
  assert.deepEqual(calls, [
    ['search', 'oak_log', 4, 64],
    ['move', 24],
    ['search', 'oak_log', 4, 64],
    ['move', 24],
    ['search', 'oak_log', 4, 64]
  ])
})

test('exploreForBlock gives up after exhausting attempts', async () => {
  const calls = []
  const result = await exploreForBlock(
    {},
    'oak_log',
    64,
    24,
    2,
    {
      goToNearestBlock: async () => {
        calls.push('search')
        return false
      },
      moveAway: async () => {
        calls.push('move')
        return true
      },
      log: () => {}
    }
  )

  assert.equal(result, false)
  assert.deepEqual(calls, ['search', 'move', 'search'])
})
