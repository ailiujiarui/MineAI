import test from 'node:test'
import assert from 'node:assert/strict'
import { buildVoiceTranscriptPayload } from '../../src/voice/client.js'

test('buildVoiceTranscriptPayload fills defaults for quick CLI injection', () => {
  const payload = buildVoiceTranscriptPayload({
    text: 'collect wood',
    speakerId: 'captain'
  })

  assert.equal(payload.text, 'collect wood')
  assert.equal(payload.speakerId, 'captain')
  assert.equal(payload.source, 'cli')
  assert.equal(typeof payload.timestamp, 'number')
})

test('buildVoiceTranscriptPayload preserves metadata and explicit source', () => {
  const payload = buildVoiceTranscriptPayload({
    text: 'what are you doing',
    source: 'doubao-asr',
    metadata: {
      language: 'zh-CN'
    }
  })

  assert.equal(payload.source, 'doubao-asr')
  assert.deepEqual(payload.metadata, { language: 'zh-CN' })
})

test('buildVoiceTranscriptPayload keeps microphone trigger metadata', () => {
  const payload = buildVoiceTranscriptPayload({
    text: 'follow me',
    source: 'mic',
    metadata: {
      triggerMode: 'voice',
      wakeReason: 'wake-window'
    }
  })

  assert.equal(payload.source, 'mic')
  assert.equal(payload.metadata.triggerMode, 'voice')
  assert.equal(payload.metadata.wakeReason, 'wake-window')
})
