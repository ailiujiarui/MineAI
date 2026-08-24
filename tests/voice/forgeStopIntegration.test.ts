import test from 'node:test'
import assert from 'node:assert/strict'

import { Agent } from '../../src/agent/agent.js'
import { serverProxy } from '../../src/agent/mindserver_proxy.js'
import { createCommandIntent } from '../../src/voice/voiceRuntime.js'
import { setSettings } from '../../src/agent/settings.js'

function createStopAgent(sequence: string[], responses: string[]) {
  const agent: any = Object.create(Agent.prototype)
  agent.voice_reply_tracker = { arm() { sequence.push('arm') } }
  agent.actions = {
    async stop() { sequence.push('mineflayer-stop') },
    cancelResume() { sequence.push('cancel-resume') }
  }
  agent.clearBotLogs = () => sequence.push('clear-logs')
  agent.bot = { emit(event: string) { sequence.push(`bot-${event}`) } }
  agent.self_prompter = { isActive() { return false } }
  agent.routeResponse = async (_speaker: string, message: string) => responses.push(message)
  return agent
}

test('Chinese stop intent stops Mineflayer before requesting Forge stop and reports accepted ack', async () => {
  const sequence: string[] = []
  const responses: string[] = []
  const agent = createStopAgent(sequence, responses)
  const original = serverProxy.requestForgeStop
  serverProxy.requestForgeStop = async () => {
    sequence.push('forge-stop')
    return { status: 'ok', ack: { status: 'ok', detail: 'accepted' } }
  }

  try {
    await agent.handleVoiceIntent(createCommandIntent('!stop'), {
      source: 'mic',
      speakerId: 'mic_user',
      text: '停止',
      metadata: { triggerMode: 'voice' }
    })

    assert.ok(sequence.indexOf('mineflayer-stop') < sequence.indexOf('forge-stop'))
    assert.match(responses[0], /已停止 Mineflayer/)
    assert.match(responses[0], /Forge 客户端已接受停止指令/)
  } finally {
    serverProxy.requestForgeStop = original
  }
})

test('Forge unavailability never rolls back the local Mineflayer stop', async () => {
  const sequence: string[] = []
  const responses: string[] = []
  const agent = createStopAgent(sequence, responses)
  const original = serverProxy.requestForgeStop
  serverProxy.requestForgeStop = async () => {
    sequence.push('forge-unavailable')
    return { status: 'unavailable', message: 'bridge offline' }
  }

  try {
    await agent.handleVoiceIntent(createCommandIntent('!stop'), {
      source: 'chat',
      speakerId: 'pih',
      text: '停止',
      metadata: {}
    })

    assert.ok(sequence.includes('mineflayer-stop'))
    assert.match(responses[0], /未找到可用的 Forge 客户端/)
  } finally {
    serverProxy.requestForgeStop = original
  }
})

test('disabled Forge action channel reports local-only stop without a bridge request', async () => {
  const sequence: string[] = []
  const responses: string[] = []
  const agent = createStopAgent(sequence, responses)
  const original = serverProxy.requestForgeStop
  setSettings({ forge_action: { enabled: false } })
  serverProxy.requestForgeStop = async () => {
    sequence.push('forge-stop-should-not-run')
    return { status: 'ok' }
  }

  try {
    await agent.handleVoiceIntent(createCommandIntent('!stop'), {
      source: 'chat', speakerId: 'pih', text: '停止', metadata: {}
    })

    assert.ok(sequence.includes('mineflayer-stop'))
    assert.equal(sequence.includes('forge-stop-should-not-run'), false)
    assert.deepEqual(responses, ['已停止当前动作。'])
  } finally {
    serverProxy.requestForgeStop = original
    setSettings({ forge_action: { enabled: true } })
  }
})

test('permission denial prevents both Mineflayer and Forge stop execution', async () => {
  const sequence: string[] = []
  const responses: string[] = []
  const agent = createStopAgent(sequence, responses)
  agent.command_permission_policy = {
    evaluate() { return { allowed: false, reason: 'permission-denied', message: 'denied stop' } }
  }
  const original = serverProxy.requestForgeStop
  serverProxy.requestForgeStop = async () => {
    sequence.push('forge-stop-should-not-run')
    return { status: 'ok' }
  }

  try {
    await agent.handleVoiceIntent(createCommandIntent('!stop'), {
      source: 'mic', speakerId: 'guest', text: '停止', metadata: { triggerMode: 'voice' }
    })
    assert.equal(sequence.includes('mineflayer-stop'), false)
    assert.equal(sequence.includes('forge-stop-should-not-run'), false)
    assert.deepEqual(responses, ['denied stop'])
  } finally {
    serverProxy.requestForgeStop = original
  }
})
