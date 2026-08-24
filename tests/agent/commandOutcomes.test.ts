import test from 'node:test'
import assert from 'node:assert/strict'

import { ActionManager, actionOutputIndicatesFailure } from '../../src/agent/action_manager.js'
import {
  COMMAND_OUTCOMES,
  executeCommandWithOutcome
} from '../../src/agent/commands/index.js'
import { CommandPermissionPolicy } from '../../src/safety/commandPermissionPolicy.js'

test('command outcomes distinguish invalid, denied, and confirmation-required requests', async () => {
  const agent = {
    name: 'NPC',
    command_permission_policy: new CommandPermissionPolicy({ operators: ['Admin'] })
  }

  const invalid = await executeCommandWithOutcome(agent, '!doesNotExist')
  assert.equal(invalid.outcome, COMMAND_OUTCOMES.INVALID)

  const denied = await executeCommandWithOutcome(agent, '!restart', { actor: 'Steve', origin: 'user' })
  assert.equal(denied.outcome, COMMAND_OUTCOMES.DENIED)

  const confirmation = await executeCommandWithOutcome(
    agent,
    '!clearChat',
    { actor: 'Admin', origin: 'model' }
  )
  assert.equal(confirmation.outcome, COMMAND_OUTCOMES.CONFIRMATION_REQUIRED)
  assert.equal(agent.command_permission_policy.hasPendingConfirmation('Admin'), true)
})

test('autonomous high-risk requests require a real player without leaving a bot-owned confirmation', async () => {
  const policy = new CommandPermissionPolicy({ operators: ['NPC'] })
  const agent = { name: 'NPC', command_permission_policy: policy }

  const result = await executeCommandWithOutcome(
    agent,
    '!clearChat',
    { actor: 'NPC', origin: 'system' }
  )

  assert.equal(result.outcome, COMMAND_OUTCOMES.PLAYER_ACTION_REQUIRED)
  assert.equal(policy.hasPendingConfirmation('NPC'), false)
})

test('full armor-set transfer is covered by one player confirmation', async () => {
  const policy = new CommandPermissionPolicy({
    default_role: 'player',
    high_risk_actions: ['!giveArmorSet'],
    require_confirmation: true
  })
  const agent = { name: 'NPC', command_permission_policy: policy }

  const result = await executeCommandWithOutcome(
    agent,
    '!giveArmorSet("pih", "netherite")',
    { actor: 'pih', origin: 'model' }
  )

  assert.equal(result.outcome, COMMAND_OUTCOMES.CONFIRMATION_REQUIRED)
  assert.equal(policy.hasPendingConfirmation('pih'), true)
})

test('action wrappers preserve explicit false and interruption outcomes', async () => {
  const createAgent = (runAction: Function) => ({
    name: 'NPC',
    command_permission_policy: new CommandPermissionPolicy({ enabled: false }),
    actions: { runAction },
    bot: { players: {}, output: '' }
  })

  const failedAgent = createAgent(async (_label: string, actionFn: Function) => {
    const value = await actionFn()
    return { success: value !== false, message: 'Could not find player Alex.', interrupted: false, timedout: false }
  })
  const failed = await executeCommandWithOutcome(failedAgent, '!attackPlayer("Alex")')
  assert.equal(failed.outcome, COMMAND_OUTCOMES.FAILED)
  assert.match(failed.result, /Could not find player/)

  const interruptedAgent = createAgent(async () => ({
    success: false,
    message: 'Stopped by player.',
    interrupted: true,
    timedout: false
  }))
  const interrupted = await executeCommandWithOutcome(interruptedAgent, '!attackPlayer("Alex")')
  assert.equal(interrupted.outcome, COMMAND_OUTCOMES.INTERRUPTED)
})

test('ActionManager treats an explicit false action return as failure', async () => {
  const bot = {
    output: '',
    interrupt_code: false,
    emit() {}
  }
  const agent = {
    bot,
    clearBotLogs() {
      bot.output = ''
      bot.interrupt_code = false
    },
    requestInterrupt() {},
    self_prompter: { isActive: () => false },
    isIdle: () => true
  }
  const manager = new ActionManager(agent)

  const result = await manager.runAction('test:false', async () => false)

  assert.equal(result.success, false)
  assert.equal(result.interrupted, false)
})

test('ActionManager recognizes a final Mineflayer failure log as a failed action', async () => {
  const bot = {
    output: '',
    interrupt_code: false,
    emit() {}
  }
  const agent = {
    bot,
    clearBotLogs() {
      bot.output = ''
      bot.interrupt_code = false
    },
    requestInterrupt() {},
    self_prompter: { isActive: () => false },
    isIdle: () => true
  }
  const manager = new ActionManager(agent)

  const result = await manager.runAction('test:missing-tool', async () => {
    bot.output = "Searching for coal.\nDon't have right tools to harvest coal_ore.\n"
  })

  assert.equal(result.success, false)
  assert.match(result.message, /right tools/)
  assert.equal(actionOutputIndicatesFailure('Could not find a chest nearby.'), true)
  assert.equal(actionOutputIndicatesFailure('Could not find it at first.\nCollected 3 coal.'), false)
})

test('ActionManager clears a previous timeout before starting the next action', async () => {
  const bot = { output: '', interrupt_code: false, emit() {} }
  const agent = {
    bot,
    clearBotLogs() { bot.output = ''; bot.interrupt_code = false },
    requestInterrupt() {},
    self_prompter: { isActive: () => false },
    isIdle: () => true
  }
  const manager = new ActionManager(agent)
  manager.timedout = true

  const result = await manager.runAction('test:after-timeout', async () => true)

  assert.equal(result.success, true)
  assert.equal(result.timedout, false)
})
