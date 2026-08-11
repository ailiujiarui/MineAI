import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildGoalPauseMessage,
  buildNoCommandStopMessage,
  buildRuntimeMessage,
  buildSpawnGreeting
} from '../../src/locale/chinese.js'
import { setSettings } from '../../src/agent/settings.js'
import { handleEnglishTranslation, handleTranslation } from '../../src/utils/translator.js'

test('Chinese profile uses its display name in the spawn greeting', () => {
  assert.equal(buildSpawnGreeting({
    name: 'MineAIZH',
    display_name: '小助手',
    native_language: 'zh-CN'
  }), '你好，我是小助手。')
})

test('non-Chinese profile keeps the English spawn greeting', () => {
  assert.equal(buildSpawnGreeting({ name: 'Andy', display_name: 'Andy' }, 'en'), 'Hello world! I am Andy')
})

test('runtime lifecycle messages follow the native profile language', () => {
  assert.equal(buildRuntimeMessage('stuck', { native_language: 'zh-CN' }), '我卡住了，正在尝试脱困。')
  assert.equal(buildRuntimeMessage('free', { native_language: 'zh-CN' }), '我已经脱困了。')
  assert.equal(buildRuntimeMessage('stuck', {}, 'en'), "I'm stuck!")
  assert.match(buildGoalPauseMessage('!collectBlocks', true, { native_language: 'zh-CN' }), /已暂停目标/)
  assert.match(buildNoCommandStopMessage(3, { native_language: 'zh-CN' }), /已停止当前目标/)
})

test('Chinese-native input and output skip the translation provider', async () => {
  setSettings({ language: 'zh-CN', profile: { native_language: 'zh-CN' } })
  let calls = 0
  const provider = async () => {
    calls += 1
    return { text: 'unexpected' }
  }

  assert.equal(await handleEnglishTranslation('停止', provider), '停止')
  assert.equal(await handleTranslation('已停止。', provider), '已停止。')
  assert.equal(calls, 0)
})
