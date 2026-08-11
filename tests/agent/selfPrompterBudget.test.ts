import test from 'node:test'
import assert from 'node:assert/strict'

import { SelfPrompter } from '../../src/agent/self_prompter.js'

function createHarness() {
  const messages: string[] = []
  const agent = {
    prompter: { profile: { native_language: 'zh-CN' } },
    openChat(message: string) {
      messages.push(message)
    },
    actions: { stop: async () => {} },
    isIdle: () => true,
    handleMessage: async () => false
  }
  return { agent, messages, prompter: new SelfPrompter(agent) }
}

test('two model confirmation attempts pause the active goal once', async () => {
  const { prompter, messages } = createHarness()
  prompter.state = 1

  const first = prompter.recordCommandOutcome({
    outcome: 'denied',
    commandName: '!confirm',
    args: []
  })
  const second = prompter.recordCommandOutcome({
    outcome: 'denied',
    commandName: '!confirm',
    args: []
  })

  assert.equal(first.paused, false)
  assert.equal(second.paused, true)
  assert.equal(prompter.isPaused(), true)
  assert.equal(messages.length, 1)
  assert.match(messages[0], /最后失败的操作：!confirm/)

  prompter.handlePlayerInstruction()
  assert.equal(prompter.isActive(), true)
  assert.equal(prompter.total_failures, 0)
})

test('five different failed iterations pause the goal and success clears the budget', () => {
  const { prompter, messages } = createHarness()
  prompter.state = 1

  for (let index = 0; index < 4; index++) {
    const result = prompter.recordCommandOutcome({
      outcome: 'invalid',
      commandName: `!bad${index}`,
      args: []
    })
    assert.equal(result.paused, false)
  }
  prompter.recordCommandOutcome({ outcome: 'success', commandName: '!inventory', args: [] })
  assert.equal(prompter.total_failures, 0)

  for (let index = 0; index < 5; index++) {
    prompter.recordCommandOutcome({
      outcome: 'denied',
      commandName: `!blocked${index}`,
      args: []
    })
  }
  assert.equal(prompter.isPaused(), true)
  assert.equal(messages.length, 1)
})

test('confirmation-required waits without spending the failure budget', () => {
  const { prompter } = createHarness()
  prompter.state = 1

  const result = prompter.recordCommandOutcome({
    outcome: 'confirmation-required',
    commandName: '!givePlayer',
    args: ['Steve', 'coal', 1],
    actor: 'Steve'
  })

  assert.equal(result.waitingForPlayer, true)
  assert.equal(prompter.isWaitingForPlayer(), true)
  assert.equal(prompter.total_failures, 0)

  prompter.handlePlayerInstruction('Alex')
  assert.equal(prompter.isWaitingForPlayer(), true)

  prompter.handlePlayerInstruction('steve')
  assert.equal(prompter.isActive(), true)
  assert.equal(prompter.total_failures, 0)
})

test('each self-prompt iteration requests at most one model command', async () => {
  const { agent, prompter } = createHarness()
  const limits: number[] = []
  agent.handleMessage = async (_source: string, _message: string, maxResponses: number) => {
    limits.push(maxResponses)
    prompter.interrupt = true
    return true
  }
  prompter.state = 1

  await prompter.startLoop()

  assert.deepEqual(limits, [1])
})
