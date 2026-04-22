import test from 'node:test'
import assert from 'node:assert/strict'
import { GPT } from '../../src/models/gpt.js'

test('gpt model uses responses api when wire_api is responses even with custom base url', async () => {
  const calls = []
  const model = new GPT('gpt-5.4', 'https://gptcodex.top', {}, 'responses')
  model.openai = {
    responses: {
      create: async (payload) => {
        calls.push(['responses', payload])
        return { output_text: 'ok***' }
      }
    },
    chat: {
      completions: {
        create: async (payload) => {
          calls.push(['chat', payload])
          return { choices: [{ message: { content: 'wrong' }, finish_reason: 'stop' }] }
        }
      }
    }
  }

  const result = await model.sendRequest([{ role: 'user', content: 'hello' }], 'system prompt')

  assert.equal(result, 'ok')
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'responses')
})

test('gpt model uses chat completions when wire_api is chat.completions', async () => {
  const calls = []
  const model = new GPT('gpt-5.4', 'https://gptcodex.top', {}, 'chat.completions')
  model.openai = {
    responses: {
      create: async (payload) => {
        calls.push(['responses', payload])
        return { output_text: 'wrong***' }
      }
    },
    chat: {
      completions: {
        create: async (payload) => {
          calls.push(['chat', payload])
          return { choices: [{ message: { content: 'chat-ok' }, finish_reason: 'stop' }] }
        }
      }
    }
  }

  const result = await model.sendRequest([{ role: 'user', content: 'hello' }], 'system prompt')

  assert.equal(result, 'chat-ok')
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'chat')
})
