import test from 'node:test'
import assert from 'node:assert/strict'

import { createTtsAdapter } from '../../src/voice/providers/index.js'

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

test('createTtsAdapter uses v3 HTTP TTS when realtime mode is reserved for ASR', () => {
  const adapter = createTtsAdapter({
    provider: 'doubao',
    doubao: {
      mode: 'realtime',
      ttsMode: 'v3',
      apiKey: 'api-key-1'
    }
  })

  assert.equal(adapter.constructor.name, 'DoubaoVoiceAdapter')
  assert.equal(adapter.endpoint, 'https://openspeech.bytedance.com/api/v3/tts/create')
})
