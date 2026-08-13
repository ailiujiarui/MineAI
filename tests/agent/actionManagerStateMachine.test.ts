import test from 'node:test'
import assert from 'node:assert/strict'
import { ActionManager } from '../../src/agent/action_manager.js'

test('ActionManager delegates enabled actions to the execution state machine', async () => {
  const calls: string[] = []
  const agent: any = {
    bot: { interrupt_code: false, output: '', emit: () => {} },
    clearBotLogs: () => {},
    execution_state_machine: {
      enabled: true,
      stop: async () => calls.push('stop'),
      submit: async (action: any) => {
        calls.push(`submit:${action.kind}`)
        return action.run(new AbortController().signal)
      }
    }
  }
  const manager = new ActionManager(agent)
  const result = await manager.runAction('action:follow', async () => ({ success: true }))
  assert.equal(result.success, true)
  assert.deepEqual(calls, ['stop', 'submit:action:follow'])
})

test('ActionManager keeps the legacy path when state machine is disabled', async () => {
  const agent: any = {
    execution_state_machine: { enabled: false },
    bot: { interrupt_code: false, output: '', emit: () => {} },
    clearBotLogs: () => {},
    requestInterrupt: () => {},
    isIdle: () => true,
    self_prompter: { isActive: () => false }
  }
  const manager = new ActionManager(agent)
  const result = await manager.runAction('action:noop', async () => true)
  assert.equal(result.success, true)
})
