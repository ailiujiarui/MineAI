import test from 'node:test'
import assert from 'node:assert/strict'
import { decideRecoveryAction } from '../../src/autonomy/recovery.js'

test('returns low_food recovery when hunger is critically low', () => {
  const recovery = decideRecoveryAction({
    hunger: 7,
    health: 20,
    hostileCount: 0,
    dangerScore: 0
  })

  assert.equal(recovery?.stage, 'recover_food')
  assert.match(recovery?.goalPrompt ?? '', /food/i)
  assert.match(recovery?.goalPrompt ?? '', /!inventory/)
})

test('returns emergency retreat when health is low and hostiles are nearby', () => {
  const recovery = decideRecoveryAction({
    hunger: 18,
    health: 6,
    hostileCount: 2,
    dangerScore: 8
  })

  assert.equal(recovery?.stage, 'emergency_retreat')
  assert.match(recovery?.goalPrompt ?? '', /retreat|escape|surface/i)
  assert.match(recovery?.goalPrompt ?? '', /!goToSurface/)
})

test('returns danger reset when danger is high even if health is still okay', () => {
  const recovery = decideRecoveryAction({
    hunger: 18,
    health: 18,
    hostileCount: 4,
    dangerScore: 10
  })

  assert.equal(recovery?.stage, 'stabilize_safety')
  assert.match(recovery?.goalPrompt ?? '', /safe/i)
  assert.match(recovery?.goalPrompt ?? '', /!stats/)
})

test('returns null when the bot is healthy and safe', () => {
  const recovery = decideRecoveryAction({
    hunger: 18,
    health: 20,
    hostileCount: 0,
    dangerScore: 0
  })

  assert.equal(recovery, null)
})
