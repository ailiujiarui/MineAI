import test from 'node:test'
import assert from 'node:assert/strict'

import { recoverAgentFromDeath } from '../../src/agent/deathRecovery.js'

test('recoverAgentFromDeath stops actions, clears interrupt state, and re-emits idle', async () => {
  const calls: string[] = []
  const agent = {
    actions: {
      cancelResume() {
        calls.push('cancelResume')
      },
      async stop() {
        calls.push('stop')
      }
    },
    clearBotLogs() {
      calls.push('clearBotLogs')
      this.bot.interrupt_code = false
    },
    bot: {
      interrupt_code: true,
      emit(eventName: string) {
        calls.push(`emit:${eventName}`)
      }
    }
  }

  await recoverAgentFromDeath(agent as any)

  assert.deepEqual(calls, ['cancelResume', 'stop', 'clearBotLogs', 'emit:idle'])
  assert.equal(agent.bot.interrupt_code, false)
})
