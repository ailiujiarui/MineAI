import test from 'node:test'
import assert from 'node:assert/strict'
import { CompanionRuntime } from '../../src/companion/companionRuntime.js'

test('task-with-companion-tone mode produces warm task narration', async () => {
  const runtime = new CompanionRuntime({
    mode: 'task-with-companion-tone'
  })

  const message = await runtime.describeTaskUpdate({
    stage: 'reach_iron',
    nextCommand: '!collectBlocks("iron_ore", 3)'
  })

  assert.match(message ?? '', /iron/i)
  assert.match(message ?? '', /I('| a)m|I'll|we/i)
})

test('task mode keeps task narration concise', async () => {
  const runtime = new CompanionRuntime({
    mode: 'task'
  })

  const message = await runtime.describeTaskUpdate({
    stage: 'secure_furnace_and_light',
    nextCommand: '!craftRecipe("furnace", 1)'
  })

  assert.match(message ?? '', /furnace/i)
  assert.equal(message?.includes('buddy'), false)
})

test('companion mode responds to social input without turning it into a goal', async () => {
  const runtime = new CompanionRuntime({
    mode: 'companion'
  })

  const reply = await runtime.respondToCompanionInput({
    speakerId: 'tester',
    text: 'how are you doing?'
  })

  assert.match(reply ?? '', /doing|here|with you|ready/i)
})

test('task-with-companion-tone mode explains task failures supportively', async () => {
  const runtime = new CompanionRuntime({
    mode: 'task-with-companion-tone'
  })

  const message = await runtime.describeTaskFailure({
    stage: 'reach_iron',
    failedCommand: '!collectBlocks("iron_ore", 3)',
    reason: 'Could not find iron_ore in 96 blocks.'
  })

  assert.match(message ?? '', /iron/i)
  assert.match(message ?? '', /try|next|safe|adjust/i)
})

test('Chinese companion runtime keeps language for replies and task narration', async () => {
  const runtime = new CompanionRuntime({
    mode: 'task-with-companion-tone',
    language: 'zh-cn'
  })

  const reply = await runtime.respondToCompanionInput({ speakerId: 'pih', text: '你好' })
  const update = await runtime.describeTaskUpdate({ stage: '采煤', nextCommand: '!inventory' })
  const failure = await runtime.describeTaskFailure({
    stage: '采煤',
    failedCommand: '!collectBlocks("coal_ore", 3)',
    reason: '没有合适的工具'
  })

  assert.match(reply ?? '', /我陪着你/)
  assert.match(update ?? '', /我正在处理采煤/)
  assert.match(failure ?? '', /遇到问题/)
  assert.doesNotMatch(`${reply}${update}${failure}`, /I'm|Task update|Reason:/)
})
