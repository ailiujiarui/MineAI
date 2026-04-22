import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createDeathDisconnectGuard,
  shouldIgnoreDisconnectAfterDeath
} from '../../src/agent/deathDisconnectGuard.js'

test('shouldIgnoreDisconnectAfterDeath only ignores disconnects inside the grace window', () => {
  const deathTimestamp = 1_000

  assert.equal(
    shouldIgnoreDisconnectAfterDeath({
      lastDeathAt: deathTimestamp,
      now: deathTimestamp + 2_000,
      graceMs: 5_000
    }),
    true
  )

  assert.equal(
    shouldIgnoreDisconnectAfterDeath({
      lastDeathAt: deathTimestamp,
      now: deathTimestamp + 6_000,
      graceMs: 5_000
    }),
    false
  )
})

test('death disconnect guard records death and suppresses one transient disconnect', () => {
  const guard = createDeathDisconnectGuard({ graceMs: 5_000, now: () => 10_000 })

  guard.markDeath()
  assert.equal(guard.shouldSuppressDisconnect(), true)
})

test('death disconnect guard does not suppress disconnects when no recent death was recorded', () => {
  const guard = createDeathDisconnectGuard({ graceMs: 5_000, now: () => 10_000 })

  assert.equal(guard.shouldSuppressDisconnect(), false)
})
