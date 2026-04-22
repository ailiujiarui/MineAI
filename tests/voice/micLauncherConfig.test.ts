import test from 'node:test'
import assert from 'node:assert/strict'

import { buildVoiceMicChildArgs, resolveVoiceMicPythonCommand } from '../../src/voice/micLauncherConfig.js'

test('voice mic launcher prefers explicit CLI port over env and settings defaults', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: ['--port', '31000'],
    envPort: '28127',
    settingsPort: 8080
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '31000'
  ])
})

test('voice mic launcher falls back to MINDSERVER_PORT when no CLI port is provided', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: [],
    envPort: '28127',
    settingsPort: 8080
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28127'
  ])
})

test('voice mic launcher falls back to settings mindserver port when env override is absent', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: ['--chunk-ms', '40'],
    envPort: '',
    settingsPort: 28126
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28126',
    '--chunk-ms',
    '40'
  ])
})

test('voice mic launcher applies configured mic defaults when CLI args do not override them', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: [],
    envPort: '',
    settingsPort: 28126,
    micSettings: {
      speaker_id: 'desk_user',
      sample_rate: 22050,
      chunk_ms: 40,
      device: 'Microphone Array'
    }
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28126',
    '--speaker-id',
    'desk_user',
    '--sample-rate',
    '22050',
    '--chunk-ms',
    '40',
    '--device',
    'Microphone Array'
  ])
})

test('voice mic launcher respects explicit CLI mic args over configured defaults', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: ['--sample-rate', '48000', '--device', 'USB Mic'],
    envPort: '',
    settingsPort: 28126,
    micSettings: {
      sample_rate: 22050,
      chunk_ms: 40,
      device: 'Microphone Array'
    }
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28126',
    '--chunk-ms',
    '40',
    '--sample-rate',
    '48000',
    '--device',
    'USB Mic'
  ])
})

test('voice mic launcher resolves python command from settings before falling back to the default venv path', () => {
  assert.equal(
    resolveVoiceMicPythonCommand('.\\.local\\custom-voice\\python.exe'),
    '.\\.local\\custom-voice\\python.exe'
  )
  assert.equal(
    resolveVoiceMicPythonCommand(''),
    '.\\.local\\voice-mic-venv\\Scripts\\python.exe'
  )
})

test('voice mic launcher propagates configured mindserver host and doubao realtime settings', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: [],
    envPort: '',
    settingsPort: 28126,
    mindserverHost: '192.168.0.8',
    doubaoRealtimeSettings: {
      endpoint: 'wss://example.invalid/realtime',
      resourceId: 'volc.custom.dialog',
      appKey: 'custom-app-key',
      model: '9.9.9.9'
    }
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28126',
    '--mindserver-host',
    '192.168.0.8',
    '--endpoint',
    'wss://example.invalid/realtime',
    '--resource-id',
    'volc.custom.dialog',
    '--app-key',
    'custom-app-key',
    '--model',
    '9.9.9.9'
  ])
})

test('voice mic launcher keeps explicit CLI realtime args over configured defaults', () => {
  const childArgs = buildVoiceMicChildArgs({
    scriptPath: 'scripts/voice-mic-listener.py',
    agent: 'gpt',
    remainingArgs: ['--model', '1.2.3.4', '--mindserver-host', '10.0.0.2'],
    envPort: '',
    settingsPort: 28126,
    mindserverHost: '192.168.0.8',
    doubaoRealtimeSettings: {
      model: '9.9.9.9'
    }
  })

  assert.deepEqual(childArgs, [
    'scripts/voice-mic-listener.py',
    '--agent',
    'gpt',
    '--port',
    '28126',
    '--model',
    '1.2.3.4',
    '--mindserver-host',
    '10.0.0.2'
  ])
})
