import test from 'node:test'
import assert from 'node:assert/strict'
import { OpenVoiceLocalTtsAdapter, resolveOpenVoiceProfile } from '../../src/voice/providers/openVoiceLocal.js'

test('openvoice local adapter builds python command invocation', async () => {
  const calls = []
  const adapter = new OpenVoiceLocalTtsAdapter({
    pythonCommand: 'python',
    scriptPath: 'scripts/openvoice_tts.py',
    referenceAudio: 'voice.wav',
    language: 'EN_V2',
    spawnImpl: (command, args) => {
      calls.push([command, args])
      return {
        stdout: {
          on() {}
        },
        stderr: {
          on() {}
        },
        on(event, handler) {
          if (event === 'close') {
            setTimeout(() => handler(0), 0)
          }
        }
      }
    },
    readFileImpl: async () => Buffer.from([1, 2, 3])
  })

  const result = await adapter.synthesize({
    text: 'hello world',
    channel: 'chat'
  })

  assert.equal(result.provider, 'openvoice-local')
  assert.equal(result.mimeType, 'audio/wav')
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'python')
  assert.equal(calls[0][1][0], 'scripts/openvoice_tts.py')
  const args = calls[0][1]
  assert.equal(args[args.indexOf('--text') + 1], 'hello world')
  assert.equal(args[args.indexOf('--voice-name') + 1], 'EN-US')
  assert.equal(args[args.indexOf('--language') + 1], 'EN_V2')
})

test('resolveOpenVoiceProfile uses configured active profile by default', () => {
  const profile = resolveOpenVoiceProfile(
    { text: 'hello there', metadata: {} },
    {
      activeProfile: 'maid_soft',
      profiles: {
        default: {
          voiceName: 'EN-US',
          language: 'EN_V2'
        },
        maid_soft: {
          voiceName: 'EN-AU',
          language: 'EN_V2',
          referenceAudio: 'maid-soft.wav'
        }
      }
    }
  )

  assert.equal(profile.name, 'maid_soft')
  assert.equal(profile.voiceName, 'EN-AU')
  assert.equal(profile.referenceAudio, 'maid-soft.wav')
})

test('resolveOpenVoiceProfile honors metadata voiceProfile override', () => {
  const profile = resolveOpenVoiceProfile(
    { text: 'hello there', metadata: { voiceProfile: 'combat_cool' } },
    {
      activeProfile: 'maid_soft',
      profiles: {
        maid_soft: {
          voiceName: 'EN-AU',
          language: 'EN_V2'
        },
        combat_cool: {
          voiceName: 'EN-US',
          language: 'EN_V2',
          referenceAudio: 'combat.wav'
        }
      }
    }
  )

  assert.equal(profile.name, 'combat_cool')
  assert.equal(profile.referenceAudio, 'combat.wav')
})
