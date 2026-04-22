import test from 'node:test'
import assert from 'node:assert/strict'
import { chooseWaterEscapeTarget, shouldTriggerWaterEscape } from '../../src/agent/library/waterRecovery.js'

test('shouldTriggerWaterEscape triggers when bot is in water and not making progress', () => {
  const result = shouldTriggerWaterEscape({
    blockAtFeet: 'water',
    blockAtHead: 'water',
    horizontalSpeed: 0.01,
    stuckSeconds: 3
  })

  assert.equal(result, true)
})

test('shouldTriggerWaterEscape ignores normal movement on dry ground', () => {
  const result = shouldTriggerWaterEscape({
    blockAtFeet: 'grass_block',
    blockAtHead: 'air',
    horizontalSpeed: 0.12,
    stuckSeconds: 0
  })

  assert.equal(result, false)
})

test('shouldTriggerWaterEscape ignores shallow water when dry escape is immediately adjacent', () => {
  const result = shouldTriggerWaterEscape({
    blockAtFeet: 'water',
    blockAtHead: 'air',
    horizontalSpeed: 0.01,
    stuckSeconds: 3,
    nearestDryDistance: 1
  })

  assert.equal(result, false)
})

test('shouldTriggerWaterEscape still triggers when submerged with no quick dry escape', () => {
  const result = shouldTriggerWaterEscape({
    blockAtFeet: 'water',
    blockAtHead: 'water',
    horizontalSpeed: 0.01,
    stuckSeconds: 3,
    nearestDryDistance: 4
  })

  assert.equal(result, true)
})

test('chooseWaterEscapeTarget prefers nearby dry candidates with solid ground and higher elevation', () => {
  const result = chooseWaterEscapeTarget(
    { x: 0, y: 62, z: 0 },
    [
      { x: 2, y: 62, z: 0, solidGround: true, headClear: true, waterNeighbors: 2 },
      { x: 1, y: 63, z: 0, solidGround: true, headClear: true, waterNeighbors: 0 },
      { x: 1, y: 62, z: 1, solidGround: false, headClear: true, waterNeighbors: 0 }
    ]
  )

  assert.deepEqual(result, { x: 1, y: 63, z: 0, solidGround: true, headClear: true, waterNeighbors: 0 })
})
