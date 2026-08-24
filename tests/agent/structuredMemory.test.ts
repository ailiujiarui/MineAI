import test from 'node:test'
import assert from 'node:assert/strict'
import { StructuredMemory } from '../../src/agent/memory/structuredMemory.js'

test('structured memory upserts facts and tracks player relationship', () => {
  const memory = new StructuredMemory()
  memory.rememberFact('玩家最喜欢的作物', '小麦')
  memory.rememberFact('玩家最喜欢的作物', '胡萝卜')
  memory.noteInteraction('Steve')
  memory.noteInteraction('Steve')
  memory.addPreference('Steve', '喜欢探索')

  const result = memory.retrieve('作物', 'Steve')
  assert.equal(result.facts.length, 1)
  assert.equal(result.facts[0].value, '胡萝卜')
  assert.equal(result.relation.interactions, 2)
  assert.deepEqual(result.relation.preferences, ['喜欢探索'])
})

test('structured memory survives serialization and restores places and tasks', () => {
  const memory = new StructuredMemory()
  memory.rememberPlace('家', { x: 1, y: 2, z: 3 })
  memory.updateTask('house', 'in_progress', '收集木头')
  const restored = new StructuredMemory()
  restored.load(JSON.parse(JSON.stringify(memory.toJSON())))
  assert.deepEqual(restored.retrieve('家').places[0].position, { x: 1, y: 2, z: 3 })
  assert.equal(restored.retrieve('木头').tasks[0].status, 'in_progress')
})
