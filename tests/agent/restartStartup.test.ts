import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { Agent } from '../../src/agent/agent.js'

function createStartupHarness() {
  const bot = new EventEmitter()
  bot.autoEat = { options: null }
  const calls = { history: [], loads: [], messages: [], chats: [] }
  const agent = Object.create(Agent.prototype)
  Object.assign(agent, {
    name: 'MineAIZH',
    bot,
    last_sender: null,
    history: { async add(name, message) { calls.history.push({ name, message }) } },
    self_prompter: { async handleLoad(prompt, state) { calls.loads.push({ prompt, state }) } },
    async handleMessage(source, message, priority) { calls.messages.push({ source, message, priority }) },
    openChat(message) { calls.chats.push(message) }
  })
  return { agent, calls }
}

test('internal restart restores an active goal as paused without invoking conversation or public chat', async () => {
  const { agent, calls } = createStartupHarness()

  await agent._setupEventHandlers({
    self_prompt: '收集煤炭',
    self_prompting_state: 1
  }, null, { restartCause: 'unexpected-exit:1:none' })

  assert.deepEqual(calls.loads, [{ prompt: '收集煤炭', state: 2 }])
  assert.equal(calls.messages.length, 0)
  assert.equal(calls.chats.length, 0)
  assert.match(calls.history[0].message, /^\[Internal lifecycle\]/)
})

test('normal configured init_message keeps its conversational startup behavior', async () => {
  const { agent, calls } = createStartupHarness()

  await agent._setupEventHandlers(null, '你好，请介绍自己', {})

  assert.deepEqual(calls.messages, [{
    source: 'system',
    message: '你好，请介绍自己',
    priority: 2
  }])
  assert.equal(calls.chats.length, 0)
})
