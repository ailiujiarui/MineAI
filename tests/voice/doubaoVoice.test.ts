import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DoubaoVoiceAdapter,
  buildDoubaoAuthHeaders,
  buildDoubaoV3TtsPayload
} from '../../src/voice/providers/doubaoVoice.js'

test('buildDoubaoAuthHeaders uses only the new-console API key', () => {
  assert.deepEqual(buildDoubaoAuthHeaders({ apiKey: 'api-key-123' }), {
    'X-Api-Key': 'api-key-123',
    'Content-Type': 'application/json'
  })
})

test('buildDoubaoV3TtsPayload matches the v3 create request contract', () => {
  assert.deepEqual(buildDoubaoV3TtsPayload({
    model: 'seed-audio-1.0',
    text: 'hello',
    textPrompt: 'Speak warmly.',
    format: 'mp3',
    sampleRate: 48000,
    pitchRate: 2,
    speechRate: -1,
    loudnessRate: 1,
    watermark: { enabled: false }
  }), {
    model: 'seed-audio-1.0',
    text_prompt: 'Speak warmly.',
    audio_config: {
      format: 'mp3',
      sample_rate: 48000,
      pitch_rate: 2,
      speech_rate: -1,
      loudness_rate: 1
    },
    watermark: { enabled: false }
  })
})

test('DoubaoVoiceAdapter synthesizes through the v3 create endpoint', async () => {
  const calls = []
  const adapter = new DoubaoVoiceAdapter({
    apiKey: 'api-key-1',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        async json() {
          return { code: 3000, data: Buffer.from('audio').toString('base64') }
        }
      }
    }
  })

  const result = await adapter.synthesize({ text: 'hello', metadata: {} })
  const [url, options] = calls[0]
  const body = JSON.parse(options.body)

  assert.equal(String(url), 'https://openspeech.bytedance.com/api/v3/tts/create')
  assert.equal(options.headers['X-Api-Key'], 'api-key-1')
  assert.equal(body.model, 'seed-audio-1.0')
  assert.equal(body.text_prompt, 'hello')
  assert.equal(result.provider, 'doubao')
  assert.equal(result.mimeType, 'audio/mp3')
})
