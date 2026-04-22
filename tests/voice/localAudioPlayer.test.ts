import test from 'node:test'
import assert from 'node:assert/strict'
import { createPlaybackPlan } from '../../src/voice/localAudioPlayer.js'

test('createPlaybackPlan uses wav extension for wav mime type', () => {
  const plan = createPlaybackPlan('audio/wav')
  assert.equal(plan.extension, '.wav')
})

test('createPlaybackPlan uses mp3 extension for mpeg mime type', () => {
  const plan = createPlaybackPlan('audio/mpeg')
  assert.equal(plan.extension, '.mp3')
})
