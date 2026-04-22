import test from 'node:test'
import assert from 'node:assert/strict'
import { createVoiceReplyTracker, isSkippableVoiceReplyText, shouldSpeakVoiceReply } from '../../src/voice/replySpeakPolicy.js'

test('voice reply tracker only allows one spoken reply after voice trigger', () => {
  const tracker = createVoiceReplyTracker()

  tracker.arm({
    source: 'mic',
    metadata: {
      triggerMode: 'voice'
    }
  })

  assert.equal(tracker.shouldSpeak('voice-triggered-only'), true)
  assert.equal(tracker.shouldSpeak('voice-triggered-only'), false)
})

test('voice reply tracker allows normal speech when mode is not restricted', () => {
  const tracker = createVoiceReplyTracker()

  assert.equal(tracker.shouldSpeak('always'), true)
  assert.equal(tracker.shouldSpeak('always'), true)
})

test('voice reply tracker ignores non-voice events', () => {
  const tracker = createVoiceReplyTracker()

  tracker.arm({
    source: 'cli',
    metadata: {}
  })

  assert.equal(tracker.shouldSpeak('voice-triggered-only'), false)
})

test('empty spoken text does not consume pending voice reply', () => {
  const tracker = createVoiceReplyTracker()

  tracker.arm({
    source: 'mic',
    metadata: {
      triggerMode: 'voice'
    }
  })

  assert.equal(shouldSpeakVoiceReply(tracker, 'voice-triggered-only', ''), false)
  assert.equal(shouldSpeakVoiceReply(tracker, 'voice-triggered-only', '我现在用语音配置了。'), true)
})

test('synthetic command acknowledgement text does not consume pending voice reply', () => {
  const tracker = createVoiceReplyTracker()

  tracker.arm({
    source: 'mic',
    metadata: {
      triggerMode: 'voice'
    }
  })

  assert.equal(isSkippableVoiceReplyText('*mic_user used goal*'), true)
  assert.equal(shouldSpeakVoiceReply(tracker, 'voice-triggered-only', '*mic_user used goal*'), false)
  assert.equal(shouldSpeakVoiceReply(tracker, 'voice-triggered-only', '好的，我跟着你。'), true)
})
