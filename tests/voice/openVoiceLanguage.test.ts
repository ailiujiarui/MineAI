import test from 'node:test'
import assert from 'node:assert/strict'

import { detectOpenVoiceProfile } from '../../src/voice/providers/openVoiceLocal.js'

test('detectOpenVoiceProfile keeps English defaults for latin text', () => {
  const profile = detectOpenVoiceProfile('Hello from bot', {
    voiceName: 'EN-US',
    language: 'EN_V2',
    zhVoiceName: 'ZH',
    zhLanguage: 'ZH'
  })

  assert.deepEqual(profile, {
    voiceName: 'EN-US',
    language: 'EN_V2'
  })
})

test('detectOpenVoiceProfile switches to Chinese for CJK text', () => {
  const profile = detectOpenVoiceProfile('你好，我在工作。', {
    voiceName: 'EN-US',
    language: 'EN_V2',
    zhVoiceName: 'ZH',
    zhLanguage: 'ZH'
  })

  assert.deepEqual(profile, {
    voiceName: 'ZH',
    language: 'ZH'
  })
})
