import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DoubaoAsrClient,
  DoubaoVoiceAdapter,
  DoubaoVoiceCloneClient,
  buildDoubaoAuthHeaders,
  buildDoubaoClonePayload,
  buildDoubaoTtsPayload,
  getDoubaoCloneResourceId
} from '../../src/voice/providers/doubaoVoice.js'

test('buildDoubaoAuthHeaders uses Bearer token and clone resource id', () => {
  const headers = buildDoubaoAuthHeaders({
    accessToken: 'token-123',
    resourceId: 'volc.seedicl.voiceclone'
  })

  assert.equal(headers.Authorization, 'Bearer token-123')
  assert.equal(headers['Resource-Id'], 'volc.seedicl.voiceclone')
  assert.equal(headers['Content-Type'], 'application/json')
})

test('buildDoubaoAuthHeaders uses x-api-key for API key based tts requests', () => {
  const headers = buildDoubaoAuthHeaders({
    apiKey: 'api-key-123',
    resourceId: 'volc.service_type.10029'
  })

  assert.equal(headers['x-api-key'], 'api-key-123')
  assert.equal(headers.Authorization, undefined)
  assert.equal(headers['Resource-Id'], undefined)
})

test('buildDoubaoTtsPayload uses speaker id as voice_type', () => {
  const payload = buildDoubaoTtsPayload({
    appId: 'app-1',
    text: 'hello world',
    speakerId: 'S_demo',
    encoding: 'mp3',
    cluster: 'volcano_icl'
  })

  assert.equal(payload.app.appid, 'app-1')
  assert.equal(payload.audio.voice_type, 'S_demo')
  assert.equal(payload.audio.encoding, 'mp3')
  assert.equal(payload.request.operation, 'query')
})

test('buildDoubaoTtsPayload omits app credentials in API key mode', () => {
  const payload = buildDoubaoTtsPayload({
    text: 'hello world',
    speakerId: 'S_demo',
    encoding: 'mp3',
    cluster: 'volcano_icl'
  })

  assert.deepEqual(payload.app, { cluster: 'volcano_icl' })
})

test('buildDoubaoClonePayload encodes audio and text for training', () => {
  const payload = buildDoubaoClonePayload({
    appId: 'app-1',
    speakerId: 'S_demo',
    audioBuffer: Buffer.from([1, 2, 3]),
    audioFormat: 'mp3',
    text: '你好',
    language: 0,
    modelType: 5
  })

  assert.equal(payload.appid, 'app-1')
  assert.equal(payload.speaker_id, 'S_demo')
  assert.equal(payload.audios[0].audio_bytes, Buffer.from([1, 2, 3]).toString('base64'))
  assert.equal(payload.audios[0].audio_format, 'mp3')
  assert.equal(payload.model_type, 5)
})

test('getDoubaoCloneResourceId maps newer model types to current clone resources', () => {
  assert.equal(getDoubaoCloneResourceId(5), 'volc.seedicl.voiceclone')
  assert.equal(getDoubaoCloneResourceId(4), 'volc.seedicl.voiceclone')
  assert.equal(getDoubaoCloneResourceId(2), 'volc.megatts.voiceclone')
})

test('DoubaoVoiceAdapter synthesizes audio with selected profile speaker id', async () => {
  const calls = []
  const adapter = new DoubaoVoiceAdapter({
    appId: 'app-1',
    accessToken: 'token-1',
    profiles: {
      default: { speakerId: 'S_default', resourceId: 'volc.service_type.10029' },
      maid_soft: { speakerId: 'S_maid', resourceId: 'seed-icl-2.0' }
    },
    activeProfile: 'maid_soft',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        async json() {
          return {
            code: 3000,
            data: Buffer.from('audio').toString('base64')
          }
        }
      }
    }
  })
  adapter.apiKey = ''

  const result = await adapter.synthesize({
    text: 'hello',
    metadata: {}
  })

  const body = JSON.parse(calls[0][1].body)
  assert.equal(body.audio.voice_type, 'S_maid')
  assert.equal(calls[0][1].headers['Resource-Id'], 'seed-icl-2.0')
  assert.equal(result.provider, 'doubao')
  assert.equal(result.mimeType, 'audio/mp3')
})

test('DoubaoVoiceAdapter uses openspeech v1 and x-api-key when API key is configured', async () => {
  const calls = []
  const adapter = new DoubaoVoiceAdapter({
    apiKey: 'api-key-1',
    speakerId: 'S_default',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        async json() {
          return {
            code: 3000,
            data: Buffer.from('audio').toString('base64')
          }
        }
      }
    }
  })
  adapter.appId = ''
  adapter.accessToken = ''

  await adapter.synthesize({
    text: 'hello',
    metadata: {}
  })

  const [url, options] = calls[0]
  const body = JSON.parse(options.body)
  assert.equal(String(url), 'https://openspeech.bytedance.com/api/v1/tts')
  assert.equal(options.headers['x-api-key'], 'api-key-1')
  assert.equal(options.headers.Authorization, undefined)
  assert.equal(body.app.appid, undefined)
  assert.equal(body.app.token, undefined)
  assert.equal(body.request.operation, 'query')
})

test('DoubaoVoiceCloneClient uploads training audio and queries status', async () => {
  const calls = []
  const client = new DoubaoVoiceCloneClient({
    appId: 'app-1',
    accessToken: 'token-1',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        async json() {
          if (String(url).includes('/status')) {
            return {
              BaseResp: { StatusCode: 0, StatusMessage: '' },
              speaker_id: 'S_demo',
              status: 2
            }
          }
          return {
            BaseResp: { StatusCode: 0, StatusMessage: '' },
            speaker_id: 'S_demo'
          }
        }
      }
    }
  })

  const upload = await client.upload({
    speakerId: 'S_demo',
    audioBuffer: Buffer.from([1, 2, 3]),
    audioFormat: 'mp3',
    text: '你好',
    modelType: 5
  })
  const status = await client.getStatus({
    speakerId: 'S_demo',
    modelType: 5
  })

  assert.equal(upload.speakerId, 'S_demo')
  assert.equal(status.status, 2)
  assert.equal(calls.length, 2)
})

test('DoubaoAsrClient recognizes base64 audio via official flash endpoint', async () => {
  const calls = []
  const client = new DoubaoAsrClient({
    appId: 'app-1',
    accessToken: 'token-1',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        async json() {
          return {
            result: {
              text: '你好，世界'
            }
          }
        }
      }
    }
  })

  const result = await client.recognize({
    audioBuffer: Buffer.from([1, 2, 3]),
    speakerId: 'captain'
  })

  const body = JSON.parse(calls[0][1].body)
  assert.match(String(calls[0][0]), /recognize\/flash/)
  assert.equal(body.request.model_name, 'bigmodel')
  assert.equal(result.text, '你好，世界')
  assert.equal(result.source, 'doubao-asr')
})
