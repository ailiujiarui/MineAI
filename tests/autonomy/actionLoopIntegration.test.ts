import test from 'node:test'
import assert from 'node:assert/strict'
import settings, { setSettings } from '../../src/agent/settings.js'
import { AutonomyController } from '../../src/autonomy/controller.js'

function makeSnapshot() {
  return { inventoryCounts: {}, nearbyBlocks: ['oak_log'], hunger: 20, health: 20 }
}

test('autonomy action loop is bounded and preserves stage goal handling', async () => {
  const previous = { ...settings }
  setSettings({ autonomy: { agent_action_loop: { enabled: true, max_iterations: 2 } } })
  const commands: string[] = []
  const agent: any = {
    task: { data: null },
    history: { add: async () => {} },
    isIdle: () => true,
    getSkillFeedback: () => []
  }
  const controller = new AutonomyController(agent, {
    executeCommand: async (_agent: any, command: string) => { commands.push(command); return 'ok' },
    buildSnapshot: makeSnapshot
  })

  const decision = await controller.tick()
  assert.equal(decision.stage, 'gather_wood')
  assert.equal(commands.filter(command => command.startsWith('!goal(')).length, 1)
  assert.equal(commands.filter(command => command.startsWith('!collectBlocks')).length, 1)
  assert.equal(controller.loopActive, false)
  setSettings(previous)
})

test('autonomy loop guard prevents concurrent runs', async () => {
  const previous = { ...settings }
  setSettings({ autonomy: { agent_action_loop: { enabled: true, max_iterations: 2 } } })
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const agent: any = {
    task: { data: null },
    history: { add: async () => {} },
    isIdle: () => true,
    getSkillFeedback: () => []
  }
  const controller = new AutonomyController(agent, {
    executeCommand: async (_agent: any, command: string) => {
      if (command.startsWith('!collectBlocks')) await gate
      return 'ok'
    },
    buildSnapshot: makeSnapshot
  })
  const first = controller.tick()
  await new Promise(resolve => setTimeout(resolve, 0))
  const second = await controller.tick()
  assert.equal(second, null)
  release()
  await first
  setSettings(previous)
})
