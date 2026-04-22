import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createBlockLookup,
  resourceLocationKey,
  selectBestMiningTarget,
  summarizeKnownLocations
} from '../../src/autonomy/baritoneMining.js'

test('createBlockLookup matches target block names like a lightweight BlockOptionalMetaLookup', () => {
  const lookup = createBlockLookup(['iron_ore', 'deepslate_iron_ore'])

  assert.equal(lookup.has('iron_ore'), true)
  assert.equal(lookup.has('deepslate_iron_ore'), true)
  assert.equal(lookup.has('coal_ore'), false)
})

test('selectBestMiningTarget prefers closer exposed ore candidates', () => {
  const lookup = createBlockLookup(['iron_ore', 'deepslate_iron_ore'])
  const best = selectBestMiningTarget(
    { x: 0, y: 64, z: 0 },
    [
      { blockName: 'iron_ore', x: 8, y: 64, z: 0, exposedFaces: 3, distanceSq: 64 },
      { blockName: 'deepslate_iron_ore', x: 20, y: 20, z: 0, exposedFaces: 1, distanceSq: 2000 }
    ],
    lookup
  )

  assert.equal(best?.blockName, 'iron_ore')
})

test('selectBestMiningTarget ignores blocks outside the lookup filter', () => {
  const lookup = createBlockLookup(['iron_ore'])
  const best = selectBestMiningTarget(
    { x: 0, y: 64, z: 0 },
    [
      { blockName: 'coal_ore', x: 2, y: 64, z: 0, exposedFaces: 4, distanceSq: 4 }
    ],
    lookup
  )

  assert.equal(best, null)
})

test('selectBestMiningTarget skips blacklisted resource locations', () => {
  const lookup = createBlockLookup(['iron_ore'])
  const blocked = { blockName: 'iron_ore', x: 2, y: 64, z: 0, exposedFaces: 4, distanceSq: 4 }
  const fallback = { blockName: 'iron_ore', x: 8, y: 64, z: 0, exposedFaces: 1, distanceSq: 64 }
  const best = selectBestMiningTarget(
    { x: 0, y: 64, z: 0 },
    [blocked, fallback],
    lookup,
    new Set([resourceLocationKey(blocked)])
  )

  assert.deepEqual(best, fallback)
})

test('summarizeKnownLocations groups nearby tracked resources by block type', () => {
  const summary = summarizeKnownLocations([
    { blockName: 'iron_ore', x: 1, y: 50, z: 1, exposedFaces: 2, distanceSq: 10 },
    { blockName: 'iron_ore', x: 2, y: 50, z: 2, exposedFaces: 1, distanceSq: 15 },
    { blockName: 'coal_ore', x: 5, y: 60, z: 5, exposedFaces: 4, distanceSq: 70 }
  ])

  assert.deepEqual(summary, {
    iron_ore: 2,
    coal_ore: 1
  })
})
