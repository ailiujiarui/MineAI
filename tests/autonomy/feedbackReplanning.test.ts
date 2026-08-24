import test from 'node:test'
import assert from 'node:assert/strict'
import { AutonomyController } from '../../src/autonomy/controller.js'

function snapshot() {
  return { inventoryCounts: {}, nearbyBlocks: ['oak_log'], hunger: 20, health: 20 }
}

test('autonomy allows one bounded replan after a path failure', async () => {
  const commands: string[] = []
  let feedback: any = null
  const controller = new AutonomyController(
    { task: { data: null }, history: { add: async () => {} }, getSkillFeedback: () => feedback ? [feedback] : [] },
    { executeCommand: async (_agent: any, command: string) => { commands.push(command); if (command.startsWith('!collectBlocks')) feedback = { actionId: `a${commands.length}`, success: false, failureReason: 'path_failed', message: 'path failed' } }, buildSnapshot: snapshot }
  )
  await controller.tick()
  await controller.tick()
  assert.equal(commands.filter(command => command.startsWith('!collectBlocks')).length, 2)
  await controller.tick()
  assert.equal(commands.filter(command => command.startsWith('!collectBlocks')).length, 2)
})

test('autonomy does not repeat permission or inventory failures', () => {
  const controller = new AutonomyController({ task: { data: null } })
  controller.applySkillFeedback('!collectBlocks("oak_log", 4)', snapshot(), { actionId: 'denied', success: false, failureReason: 'permission_denied', message: 'denied' })
  assert.equal(controller.lastCommand, '!collectBlocks("oak_log", 4)')
  controller.applySkillFeedback('!collectBlocks("oak_log", 4)', snapshot(), { actionId: 'full', success: false, failureReason: 'inventory_full', message: 'inventory full' })
  assert.equal(controller.lastCommand, '!collectBlocks("oak_log", 4)')
})
