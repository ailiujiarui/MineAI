import test from 'node:test'
import assert from 'node:assert/strict'
import { createCommandIntent, createConversationIntent, createGoalIntent } from '../../src/voice/voiceRuntime.js'
import { applyVoiceIntent } from '../../src/voice/agentVoiceBridge.js'

test('applyVoiceIntent routes command intents to command handler', async () => {
  const seen = []
  await applyVoiceIntent(
    {
      speakerId: 'user',
      text: '!stop'
    },
    createCommandIntent('!stop'),
    {
      onCommand: async (payload) => seen.push(['command', payload]),
      onGoal: async () => seen.push(['goal']),
      onConversation: async () => seen.push(['conversation'])
    }
  )

  assert.deepEqual(seen, [['command', '!stop']])
})

test('applyVoiceIntent routes goal intents to goal handler', async () => {
  const seen = []
  await applyVoiceIntent(
    {
      speakerId: 'user',
      text: 'collect wood'
    },
    createGoalIntent('collect wood and craft tools'),
    {
      onCommand: async () => seen.push(['command']),
      onGoal: async (payload) => seen.push(['goal', payload]),
      onConversation: async () => seen.push(['conversation'])
    }
  )

  assert.deepEqual(seen, [['goal', 'collect wood and craft tools']])
})

test('applyVoiceIntent routes conversation intents to conversation handler', async () => {
  const seen = []
  await applyVoiceIntent(
    {
      speakerId: 'user',
      text: 'hello there'
    },
    createConversationIntent('hello there'),
    {
      onCommand: async () => seen.push(['command']),
      onGoal: async () => seen.push(['goal']),
      onConversation: async (payload, event) => seen.push(['conversation', payload, event.speakerId])
    }
  )

  assert.deepEqual(seen, [['conversation', 'hello there', 'user']])
})
