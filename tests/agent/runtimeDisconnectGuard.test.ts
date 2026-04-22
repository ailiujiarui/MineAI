import test from 'node:test'
import assert from 'node:assert/strict'

import { shouldHandleRuntimeDisconnect } from '../../src/agent/deathDisconnectGuard.js'

test('shouldHandleRuntimeDisconnect suppresses runtime disconnect handling during death grace period', () => {
  const agent = {
    _disconnectHandled: false,
    death_disconnect_guard: {
      shouldSuppressDisconnect() {
        return true
      }
    }
  }

  assert.equal(shouldHandleRuntimeDisconnect(agent as any), false)
})

test('shouldHandleRuntimeDisconnect blocks duplicate disconnect handling once already handled', () => {
  const agent = {
    _disconnectHandled: true,
    death_disconnect_guard: {
      shouldSuppressDisconnect() {
        return false
      }
    }
  }

  assert.equal(shouldHandleRuntimeDisconnect(agent as any), false)
})

test('shouldHandleRuntimeDisconnect allows runtime disconnect handling when no suppression is active', () => {
  const agent = {
    _disconnectHandled: false,
    death_disconnect_guard: {
      shouldSuppressDisconnect() {
        return false
      }
    }
  }

  assert.equal(shouldHandleRuntimeDisconnect(agent as any), true)
})
