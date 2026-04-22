import test from 'node:test'
import assert from 'node:assert/strict'

import { createTtsAdapter } from '../../src/voice/providers/index.js'

test('createTtsAdapter returns OpenVoice local adapter for openvoice-local provider', () => {
  const adapter = createTtsAdapter({
    provider: 'openvoice-local',
    openvoice: {
      language: 'EN_V2'
    }
  })

  assert.equal(adapter.constructor.name, 'OpenVoiceLocalTtsAdapter')
  assert.equal(adapter.language, 'EN_V2')
})

test('createTtsAdapter falls back to null adapter for unknown provider', () => {
  const adapter = createTtsAdapter({
    provider: 'unknown-provider'
  })

  assert.equal(adapter.constructor.name, 'NullTtsAdapter')
})

test('createTtsAdapter returns Doubao adapter for doubao provider', () => {
  const adapter = createTtsAdapter({
    provider: 'doubao',
    doubao: {
      appId: 'app-1',
      accessToken: 'token-1'
    }
  })

  assert.equal(adapter.constructor.name, 'DoubaoVoiceAdapter')
})
