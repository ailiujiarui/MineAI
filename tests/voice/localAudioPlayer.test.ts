import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { createPlaybackPlan } from '../../src/voice/localAudioPlayer.js'
import { playAudioBuffer } from '../../src/voice/localAudioPlayer.js'

test('createPlaybackPlan uses wav extension for wav mime type', () => {
  const plan = createPlaybackPlan('audio/wav')
  assert.equal(plan.extension, '.wav')
})

test('createPlaybackPlan uses mp3 extension for mpeg mime type', () => {
  const plan = createPlaybackPlan('audio/mpeg')
  assert.equal(plan.extension, '.mp3')
})

test('playAudioBuffer kills the player when its abort signal fires', async () => {
  const child = new EventEmitter()
  let killed = false
  child.kill = () => { killed = true; child.emit('close', null) }
  const controller = new AbortController()
  const result = playAudioBuffer(Buffer.from('audio'), 'audio/mp3', {
    signal: controller.signal,
    mkdtempImpl: async () => 'C:/tmp/game-ai-voice-test',
    writeFileImpl: async () => {},
    unlinkImpl: async () => {},
    spawnImpl: () => child
  })
  await new Promise(resolve => setImmediate(resolve))
  controller.abort()
  await assert.rejects(result, error => error?.name === 'AbortError')
  assert.equal(killed, true)
})
