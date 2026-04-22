import test from 'node:test'
import assert from 'node:assert/strict'
import settings from '../../settings.ts'

test('voice settings expose microphone listener and selective speak controls', () => {
  assert.equal(settings.voice.enabled, true)
  assert.equal(settings.voice.provider, 'doubao')
  assert.equal(settings.voice.reply_speak_mode, 'voice-triggered-only')
  assert.equal(settings.voice.mic.enabled, false)
  assert.deepEqual(settings.voice.mic.wake_phrases, ['豆包'])
  assert.deepEqual(settings.voice.mic.direct_commands, ['停止', '跟着我', '回家'])
  assert.equal(settings.voice.mic.sample_rate, 16000)
  assert.equal(settings.voice.mic.chunk_ms, 20)
})
