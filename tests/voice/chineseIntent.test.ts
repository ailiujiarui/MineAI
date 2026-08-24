import test from 'node:test'
import assert from 'node:assert/strict'
import { routeVoiceTranscript } from '../../src/voice/intentRouter.js'
import { normalizeChineseText, resolveMinecraftName, isChineseText } from '../../src/locale/chinese.js'

test('routes Chinese imperative speech to a goal intent', async () => {
  const intent = await routeVoiceTranscript({ text: '帮我采集木头', source: 'chat' }, { companionMode: 'task' })
  assert.equal(intent.kind, 'goal')
  assert.equal(intent.payload, '帮我采集木头')
})

test('routes Chinese stop speech to the safe stop command', async () => {
  const intent = await routeVoiceTranscript({ text: '停止', source: 'chat' })
  assert.equal(intent.kind, 'command')
  assert.equal(intent.payload, '!stop')

  const punctuated = await routeVoiceTranscript({ text: '停止。', source: 'chat' })
  assert.equal(punctuated.kind, 'command')
  assert.equal(punctuated.payload, '!stop')

  for (const phrase of ['停。', '暂停！', '别动', '别做了']) {
    const variant = await routeVoiceTranscript({ text: phrase, source: 'chat' })
    assert.equal(variant.kind, 'command')
    assert.equal(variant.payload, '!stop')
  }
})

test('routes high-level Chinese combat phrases to combat intents', async () => {
  assert.equal((await routeVoiceTranscript({ text: '保护我', source: 'chat' }) as any).kind, 'combat')
  assert.equal((await routeVoiceTranscript({ text: '撤退', source: 'chat' }) as any).payload, 'retreat')
  assert.equal((await routeVoiceTranscript({ text: '停火', source: 'chat' }) as any).payload, 'ceasefire')
})

test('keeps negative and interrogative stop phrases out of the stop command path', async () => {
  const negative = await routeVoiceTranscript({ text: '不要停止', source: 'chat' })
  assert.notEqual(negative.kind, 'command')

  const question = await routeVoiceTranscript({ text: '怎么停止？', source: 'chat' })
  assert.equal(question.kind, 'companion')
})

test('normalizes unambiguous follow, home, and come-here phrases to stable goals', async () => {
  const cases = [
    ['跟我来。', '跟着我'],
    ['返回基地！', '回家'],
    ['到我这里来', '过来']
  ]

  for (const [text, payload] of cases) {
    const intent = await routeVoiceTranscript({ text, source: 'chat' })
    assert.equal(intent.kind, 'goal')
    assert.equal(intent.payload, payload)
    assert.equal(intent.meta.source, 'voice-direct-goal')
  }
})

test('routes common Chinese question forms without mistaking action words for commands', async () => {
  for (const text of ['如何回家', '什么时候回家？', '你能跟着我吗？', '有没有木头']) {
    const intent = await routeVoiceTranscript({ text, source: 'chat' })
    assert.equal(intent.kind, 'companion')
  }
})

test('recognizes Chinese greetings at real phrase boundaries', async () => {
  for (const text of ['你好', '你好 豆包', '你好啊']) {
    const intent = await routeVoiceTranscript({ text, source: 'chat' })
    assert.equal(intent.kind, 'companion')
  }

  const notGreeting = await routeVoiceTranscript({ text: '你好好挖矿', source: 'chat' })
  assert.equal(notGreeting.kind, 'conversation')
})

test('normalizes common Chinese Minecraft aliases', () => {
  assert.equal(resolveMinecraftName('木头'), 'oak_log')
  assert.equal(resolveMinecraftName('“木头。”'), 'oak_log')
  assert.equal(resolveMinecraftName('铁矿石'), 'iron_ore')
  assert.equal(resolveMinecraftName('木头屋'), '木头屋')
  assert.equal(isChineseText('回家'), true)
  assert.equal(normalizeChineseText('ＡＢＣ　木头！'), 'ABC 木头!')
})
