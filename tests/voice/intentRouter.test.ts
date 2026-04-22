import test from 'node:test'
import assert from 'node:assert/strict'
import { routeVoiceTranscript } from '../../src/voice/intentRouter.js'
import { createMicWakeGate } from '../../src/voice/micWakeGate.js'

test('routes explicit command transcripts to command intent', async () => {
  const intent = await routeVoiceTranscript({
    text: '!stop',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(intent.kind, 'command')
  assert.equal(intent.payload, '!stop')
})

test('routes plain stop to command intent instead of goal intent', async () => {
  const intent = await routeVoiceTranscript({
    text: 'stop',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(intent.kind, 'command')
  assert.equal(intent.payload, '!stop')
})

test('routes imperative work instructions to goal intent in hybrid mode', async () => {
  const intent = await routeVoiceTranscript(
    {
      text: 'collect wood, make tools, and keep yourself safe',
      source: 'asr',
      speakerId: 'user'
    },
    { commandMode: 'hybrid' }
  )

  assert.equal(intent.kind, 'goal')
  assert.match(intent.payload, /collect wood/i)
})

test('routes questions to conversation intent', async () => {
  const intent = await routeVoiceTranscript({
    text: 'what are you doing right now?',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(intent.kind, 'companion')
  assert.match(intent.payload, /what are you doing/i)
})

test('routes greetings to companion intent when companion mode is enabled', async () => {
  const intent = await routeVoiceTranscript(
    {
      text: 'hey there',
      source: 'asr',
      speakerId: 'user'
    },
    {
      companionMode: 'task-with-companion-tone'
    }
  )

  assert.equal(intent.kind, 'companion')
  assert.match(intent.payload, /hey there/i)
})

test('ignores ambient microphone transcripts outside wake window', async () => {
  const intent = await routeVoiceTranscript(
    {
      text: '今天天气不错',
      source: 'mic',
      speakerId: 'user'
    },
    {
      micGate: createMicWakeGate({
        wakePhrases: ['豆包'],
        directCommands: []
      })
    }
  )

  assert.equal(intent, null)
})

test('routes post-wake microphone imperative transcripts to goal intent', async () => {
  const micGate = createMicWakeGate({
    wakePhrases: ['豆包'],
    directCommands: []
  })

  await routeVoiceTranscript(
    {
      text: '豆包',
      source: 'mic',
      speakerId: 'user',
      metadata: {}
    },
    { micGate }
  )

  const intent = await routeVoiceTranscript(
    {
      text: 'follow me',
      source: 'mic',
      speakerId: 'user',
      metadata: {}
    },
    { micGate }
  )

  assert.equal(intent.kind, 'goal')
  assert.match(intent.payload, /follow me/i)
})

test('routes microphone direct commands with chinese punctuation to goal intent', async () => {
  const intent = await routeVoiceTranscript(
    {
      text: '豆包，跟着我。',
      source: 'mic',
      speakerId: 'user',
      metadata: {}
    },
    {
      micGate: createMicWakeGate({
        wakePhrases: ['豆包'],
        directCommands: ['跟着我']
      }),
      micConfig: {
        direct_commands: ['跟着我']
      }
    }
  )

  assert.equal(intent.kind, 'goal')
  assert.equal(intent.payload, '跟着我')
})
