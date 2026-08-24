import test from 'node:test'
import assert from 'node:assert/strict'
import { extractChineseMemories } from '../../src/agent/memory/memoryExtractor.js'

test('extracts player name and preferences from explicit Chinese statements', () => {
  assert.deepEqual(extractChineseMemories('我叫小明'), [{ kind: 'fact', key: 'player_name', value: '小明' }])
  assert.deepEqual(extractChineseMemories('我喜欢种小麦。'), [{ kind: 'preference', value: '种小麦' }])
  assert.deepEqual(extractChineseMemories('我不喜欢打僵尸'), [{ kind: 'preference', value: '不喜欢打僵尸' }])
})

test('extracts explicit remember requests but ignores ordinary conversation', () => {
  const result = extractChineseMemories('请记住：我们的家在村庄北边')
  assert.equal(result[0].kind, 'fact')
  assert.equal(result[0].value, '我们的家在村庄北边')
  assert.deepEqual(extractChineseMemories('今天天气不错'), [])
})
