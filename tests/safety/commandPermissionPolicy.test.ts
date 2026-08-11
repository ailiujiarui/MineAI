import test from 'node:test'
import assert from 'node:assert/strict'
import { CommandPermissionPolicy } from '../../src/safety/commandPermissionPolicy.js'
import { executeCommand } from '../../src/agent/commands/index.js'

test('allows ordinary player actions without confirmation', () => {
  const policy = new CommandPermissionPolicy()
  const result = policy.evaluate({
    actor: 'Steve',
    origin: 'user',
    commandName: '!collectBlocks',
    commandText: '!collectBlocks("oak_log", 4)'
  })

  assert.equal(result.allowed, true)
})

test('denies operator-only commands before creating a confirmation', () => {
  const policy = new CommandPermissionPolicy()
  const result = policy.evaluate({
    actor: 'Steve',
    origin: 'user',
    commandName: '!restart',
    commandText: '!restart'
  })

  assert.equal(result.allowed, false)
  assert.equal(result.reason, 'permission-denied')
  assert.equal(policy.hasPendingConfirmation('Steve'), false)
})

test('requires and consumes a player-bound high-risk confirmation', () => {
  let now = 1_000
  const policy = new CommandPermissionPolicy({ confirmation_ttl_ms: 5_000 }, () => now)
  const request = {
    actor: 'Steve',
    origin: 'user' as const,
    commandName: '!attackPlayer',
    commandText: '!attackPlayer("Alex")'
  }

  const result = policy.evaluate(request)
  assert.equal(result.allowed, false)
  assert.equal(result.reason, 'confirmation-required')
  assert.equal(policy.consumeConfirmation('Alex'), null)

  const confirmed = policy.consumeConfirmation('steve')
  assert.equal(confirmed?.commandText, request.commandText)
  assert.equal(confirmed?.confirmed, true)
  assert.equal(policy.consumeConfirmation('Steve'), null)

  now += 1
})

test('expires confirmations and supports explicit cancellation', () => {
  let now = 10
  const policy = new CommandPermissionPolicy({ confirmation_ttl_ms: 20 }, () => now)
  const request = {
    actor: 'Steve',
    origin: 'model' as const,
    commandName: '!discard',
    commandText: '!discard("dirt", 64)'
  }

  policy.evaluate(request)
  assert.equal(policy.cancelConfirmation('Steve'), true)
  assert.equal(policy.consumeConfirmation('Steve'), null)

  policy.evaluate(request)
  now = 31
  assert.equal(policy.hasPendingConfirmation('Steve'), false)
  assert.equal(policy.consumeConfirmation('Steve'), null)
})

test('honors configured roles and bypasses internal automation', () => {
  const policy = new CommandPermissionPolicy({
    trusted_players: ['Builder'],
    operators: ['Admin'],
    command_roles: { '!placeHere': 'trusted' }
  })

  assert.equal(policy.evaluate({ actor: 'Steve', origin: 'user', commandName: '!placeHere', commandText: '!placeHere("stone")' }).allowed, false)
  assert.equal(policy.evaluate({ actor: 'builder', origin: 'user', commandName: '!placeHere', commandText: '!placeHere("stone")' }).allowed, true)
  assert.equal(policy.evaluate({ actor: 'Admin', origin: 'user', commandName: '!restart', commandText: '!restart' }).reason, 'confirmation-required')
  assert.equal(policy.evaluate({ origin: 'internal', commandName: '!restart', commandText: '!restart' }).allowed, true)
})

test('only a direct request from the same player can execute a pending command', async () => {
  let cleared = 0
  const policy = new CommandPermissionPolicy({ operators: ['Admin'] })
  const agent = {
    name: 'NPC',
    command_permission_policy: policy,
    history: { clear: () => { cleared += 1 } }
  }

  const pending = await executeCommand(agent, '!clearChat', { actor: 'Admin', origin: 'user' })
  assert.match(pending, /等待确认/)

  const modelAttempt = await executeCommand(agent, '!confirm', { actor: 'Admin', origin: 'model' })
  assert.match(modelAttempt, /只能由玩家直接发起/)
  assert.equal(cleared, 0)

  const otherPlayerAttempt = await executeCommand(agent, '!confirm', { actor: 'Steve', origin: 'user' })
  assert.match(otherPlayerAttempt, /没有待确认/)
  assert.equal(cleared, 0)

  const confirmed = await executeCommand(agent, '!confirm', { actor: 'admin', origin: 'user' })
  assert.match(confirmed, /chat history was cleared/)
  assert.equal(cleared, 1)
})
