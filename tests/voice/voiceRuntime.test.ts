import test from 'node:test'
import assert from 'node:assert/strict'
import {
  NullTtsAdapter,
  VoiceRuntime,
  createCommandIntent,
  createConversationIntent
} from '../../src/voice/voiceRuntime.js'

test('disabled runtime ignores transcripts', async () => {
  const events = []
  const runtime = new VoiceRuntime({
    enabled: false,
    ttsAdapter: new NullTtsAdapter(),
    router: async () => createCommandIntent('!goal("ignored")'),
    onIntent: async (intent) => {
      events.push(intent)
    }
  })

  const result = await runtime.handleTranscript({
    text: 'go mine wood',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(result, null)
  assert.equal(events.length, 0)
})

test('runtime routes voice transcripts into command intents', async () => {
  const events = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: new NullTtsAdapter(),
    router: async (event) => createCommandIntent(`!goal("${event.text}")`),
    onIntent: async (intent) => {
      events.push(intent)
    }
  })

  const result = await runtime.handleTranscript({
    text: 'collect wood and craft tools',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(result?.kind, 'command')
  assert.equal(events.length, 1)
  assert.match(events[0].payload, /collect wood/i)
})

test('runtime preserves transcript metadata for microphone-triggered events', async () => {
  const events = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: new NullTtsAdapter(),
    router: async () => createConversationIntent('heard'),
    onIntent: async (_intent, transcript) => {
      events.push(transcript)
    }
  })

  await runtime.handleTranscript({
    text: 'follow me',
    source: 'mic',
    speakerId: 'user',
    metadata: {
      triggerMode: 'voice',
      wakeReason: 'wake-window'
    }
  })

  assert.equal(events[0].metadata.triggerMode, 'voice')
  assert.equal(events[0].metadata.wakeReason, 'wake-window')
})

test('runtime can synthesize outbound speech through the adapter', async () => {
  const spoken = []
  const played = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: {
      async synthesize(request) {
        spoken.push(request)
        return {
          provider: 'stub',
          audio: Buffer.from('voice'),
          mimeType: 'audio/mp3'
        }
      }
    },
    playAudio: async (audio, mimeType) => {
      played.push([audio, mimeType])
    },
    router: async () => createConversationIntent('hello'),
    onIntent: async () => {}
  })

  const result = await runtime.speak({
    text: 'Heading to the tree now.',
    channel: 'status'
  })

  assert.equal(result?.provider, 'stub')
  assert.equal(spoken.length, 1)
  assert.equal(played.length, 1)
  assert.equal(spoken[0].channel, 'status')
})

test('runtime suppresses microphone for the full local playback lifecycle', async () => {
  const states = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: { async synthesize() { return { audio: Buffer.from('voice'), mimeType: 'audio/mp3' } } },
    playAudio: async () => {},
    setPlaybackState: (active) => states.push(active)
  })
  await runtime.speak({ text: '测试回复' })
  assert.deepEqual(states, [true, false])
})

test('runtime restores microphone state when playback fails', async () => {
  const states = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: { async synthesize() { return { audio: Buffer.from('voice'), mimeType: 'audio/mp3' } } },
    playAudio: async () => { throw new Error('speaker failed') },
    setPlaybackState: (active) => states.push(active)
  })
  await assert.rejects(runtime.speak({ text: '测试回复' }), /speaker failed/)
  assert.deepEqual(states, [true, false])
})

test('runtime leaves room for conversational voice control', async () => {
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: new NullTtsAdapter(),
    router: async (event) => createConversationIntent(`heard:${event.text}`),
    onIntent: async () => {}
  })

  const result = await runtime.handleTranscript({
    text: 'what are you doing right now',
    source: 'asr',
    speakerId: 'user'
  })

  assert.equal(result?.kind, 'conversation')
  assert.match(result?.payload ?? '', /heard:/)
})

test('runtime can switch active voice profile through the adapter', async () => {
  const calls = []
  const runtime = new VoiceRuntime({
    enabled: true,
    ttsAdapter: {
      setActiveProfile(profileName) {
        calls.push(profileName)
      },
      async synthesize() {
        return {
          provider: 'stub',
          audio: Buffer.alloc(0),
          mimeType: 'audio/wav'
        }
      }
    },
    onIntent: async () => {}
  })

  runtime.setVoiceProfile('maid_soft')
  assert.deepEqual(calls, ['maid_soft'])
})
