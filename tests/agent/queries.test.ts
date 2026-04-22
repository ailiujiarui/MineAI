import test from 'node:test'
import assert from 'node:assert/strict'
import { formatStatCoordinate } from '../../src/agent/commands/statsFormatting.js'

test('formatStatCoordinate keeps finite coordinates with two decimals', () => {
  assert.equal(formatStatCoordinate(12.3456), '12.35')
  assert.equal(formatStatCoordinate(-7), '-7.00')
})

test('formatStatCoordinate falls back to unknown for invalid coordinates', () => {
  assert.equal(formatStatCoordinate(Number.NaN), 'unknown')
  assert.equal(formatStatCoordinate(Number.POSITIVE_INFINITY), 'unknown')
  assert.equal(formatStatCoordinate(undefined), 'unknown')
})
