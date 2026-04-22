import test from 'node:test'
import assert from 'node:assert/strict'

import { getPlacementPauseMs } from '../../src/agent/library/skills.js'

test('getPlacementPauseMs keeps settle delays short by default', () => {
  assert.equal(getPlacementPauseMs('after_break', 0), 75)
  assert.equal(getPlacementPauseMs('after_place', 0), 75)
})

test('getPlacementPauseMs respects larger configured delay when present', () => {
  assert.equal(getPlacementPauseMs('after_break', 180), 180)
  assert.equal(getPlacementPauseMs('after_place', 120), 120)
})
