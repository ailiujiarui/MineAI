import test from 'node:test'
import assert from 'node:assert/strict'

import { Agent } from '../../src/agent/agent.js'
import settings, { setSettings } from '../../src/agent/settings.js'
import { serverProxy } from '../../src/agent/mindserver_proxy.js'
import { createVoiceReplyTracker } from '../../src/voice/replySpeakPolicy.js'
import { createAsrAdapter, createTtsAdapter } from '../../src/voice/providers/index.js'
import { VoiceRuntime, createCompanionIntent, createConversationIntent } from '../../src/voice/voiceRuntime.js'

test('realtime microphone ASR and HTTP v3 TTS coexist in one Doubao configuration', () => {
  const voiceSettings = {
    provider: 'doubao',
    doubao: {
      mode: 'realtime',
      ttsMode: 'v3',
      apiKey: 'test-api-key',
      realtime: {
        endpoint: 'wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue',
        model: '1.2.6.1'
      }
    }
  }

  const asr = createAsrAdapter(voiceSettings)
  const tts = createTtsAdapter(voiceSettings)

  assert.equal(asr?.constructor.name, 'DoubaoRealtimeAsrClient')
  assert.equal(tts.constructor.name, 'DoubaoVoiceAdapter')
  assert.equal(tts.endpoint, 'https://openspeech.bytedance.com/api/v3/tts/create')
})

test('completed microphone transcript reaches the Agent conversation handler and closes its reply scope', async () => {
  const handledMessages = []
  const agent = Object.create(Agent.prototype)
  agent.voice_reply_tracker = createVoiceReplyTracker()
  agent.handleMessage = async (...args) => handledMessages.push(args)
  agent.voice_runtime = new VoiceRuntime({
    enabled: true,
    router: async (event) => createConversationIntent(event.text),
    onIntent: async (intent, event) => agent.handleVoiceIntent(intent, event)
  })

  const result = await agent.handleVoiceTranscript({
    text: 'Doubao, collect wood for me',
    source: 'mic',
    speakerId: 'local_player',
    metadata: {
      triggerMode: 'voice',
      eventType: 'conversation.item.input_audio_transcription.completed'
    }
  })

  assert.equal(result?.kind, 'conversation')
  assert.deepEqual(handledMessages, [[
    'local_player',
    'Doubao, collect wood for me',
    1
  ]])
  assert.equal(agent.voice_reply_tracker.shouldSpeak('voice-triggered-only'), false)
})

test('companion-classified user input is still delegated to DeepSeek', async () => {
  const handledMessages = []
  const agent = Object.create(Agent.prototype)
  agent.voice_reply_tracker = createVoiceReplyTracker()
  agent.embodiment_runtime = null
  agent.handleMessage = async (...args) => handledMessages.push(args)

  await agent.handleVoiceIntent(createCompanionIntent('怎么就给一个头盔'), {
    source: 'chat',
    speakerId: 'pih',
    metadata: { bridgedFrom: 'chat' }
  })

  assert.deepEqual(handledMessages, [[
    'pih',
    '怎么就给一个头盔',
    1,
    { skipIntentRouting: true }
  ]])
})

test('TTS failure does not block Minecraft chat or MindServer output', async () => {
  const previousSettings = { ...settings }
  const previousSocket = serverProxy.socket
  const previousConsoleError = console.error
  const gameChat = []
  const serverOutput = []

  setSettings({
    language: 'en',
    only_chat_with: [],
    speak: false,
    chat_ingame: true,
    voice: {
      enabled: true,
      provider: 'doubao',
      reply_speak_mode: 'voice-triggered-only'
    }
  })
  serverProxy.socket = {
    emit(event, ...args) {
      serverOutput.push([event, ...args])
    }
  }
  console.error = () => {}

  try {
    const agent = Object.create(Agent.prototype)
    agent.name = 'MineAIZH'
    agent.shut_up = false
    agent.bot = { chat: (message) => gameChat.push(message) }
    agent.prompter = { profile: {} }
    agent.embodiment_runtime = null
    agent.voice_reply_tracker = createVoiceReplyTracker()
    agent.voice_reply_tracker.arm({ source: 'mic' })
    agent.voice_runtime = {
      speak: async () => { throw new Error('simulated TTS outage') }
    }

    await agent.openChat('I will collect wood now.')
    await new Promise((resolve) => setImmediate(resolve))

    assert.deepEqual(gameChat, ['I will collect wood now. '])
    assert.deepEqual(serverOutput, [[
      'bot-output',
      'MineAIZH',
      'I will collect wood now. '
    ]])
  } finally {
    console.error = previousConsoleError
    serverProxy.socket = previousSocket
    setSettings(previousSettings)
  }
})
