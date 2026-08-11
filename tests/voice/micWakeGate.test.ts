import test from 'node:test'
import assert from 'node:assert/strict'
import { createMicWakeGate } from '../../src/voice/micWakeGate.js'

test('wake phrase opens one-turn voice window', () => {
  const gate = createMicWakeGate({
    wakePhrases: ['豆包'],
    directCommands: []
  })

  const first = gate.process('豆包 过来')
  const second = gate.process('去砍树')

  assert.equal(first.accepted, true)
  assert.equal(first.reason, 'wake-phrase')
  assert.equal(second.accepted, true)
  assert.equal(second.reason, 'wake-window')
})

test('direct commands bypass wake phrase', () => {
  const gate = createMicWakeGate({
    wakePhrases: ['豆包'],
    directCommands: ['停止']
  })

  const result = gate.process('停止')

  assert.equal(result.accepted, true)
  assert.equal(result.reason, 'direct-command')
})

test('ambient speech is ignored outside wake window', () => {
  const gate = createMicWakeGate({
    wakePhrases: ['豆包'],
    directCommands: []
  })

  const result = gate.process('今天天气不错')

  assert.equal(result.accepted, false)
  assert.equal(result.reason, 'ambient')
})

test('wake phrase tolerates chinese punctuation and keeps direct command text', () => {
  const gate = createMicWakeGate({
    wakePhrases: ['豆包'],
    directCommands: ['跟着我']
  })

  const result = gate.process('豆包，跟着我。')

  assert.equal(result.accepted, true)
  assert.equal(result.reason, 'direct-command')
  assert.equal(result.text, '跟着我')
})

test('repeated Chinese wake phrases arm once without routing the duplicate', () => {
  const gate = createMicWakeGate({ wakePhrases: ['豆包'] })
  const result = gate.process('豆包，豆包。')
  assert.equal(result.accepted, true)
  assert.equal(result.reason, 'wake-phrase')
  assert.equal(result.text, '')
})

test('repeated inline Chinese wake phrases preserve only request content', () => {
  const gate = createMicWakeGate({ wakePhrases: ['豆包'] })
  const result = gate.process('豆包豆包给我两组钻石')
  assert.equal(result.text, '给我两组钻石')
})
