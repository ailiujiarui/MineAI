import test from 'node:test'
import assert from 'node:assert/strict'
import { createMicTranscriptAggregator } from '../../src/voice/micTranscriptAggregator.js'
import { createMicWakeGate } from '../../src/voice/micWakeGate.js'
import { VoiceRuntime, createConversationIntent } from '../../src/voice/voiceRuntime.js'

function createScheduler() {
  const tasks = []
  return {
    tasks,
    schedule(callback, delay) {
      const task = { callback, delay, cancelled: false }
      tasks.push(task)
      return task
    },
    cancelSchedule(task) {
      task.cancelled = true
    },
    runNext(delay) {
      const task = tasks.find(item => !item.cancelled && item.delay === delay)
      assert.ok(task, `missing scheduled task for ${delay}ms`)
      task.cancelled = true
      task.callback()
    }
  }
}

function micEvent(text, speakerId = 'player') {
  return { text, source: 'mic', speakerId, metadata: { transport: 'local-mic' } }
}

test('merges standalone wake phrase and multiple follow-up transcripts after silence', async () => {
  const scheduler = createScheduler()
  const aggregator = createMicTranscriptAggregator({
    wakePhrases: ['豆包'],
    wakeFollowupSilenceMs: 500,
    wakeFollowupMaxWaitMs: 8000,
    schedule: scheduler.schedule,
    cancelSchedule: scheduler.cancelSchedule
  })

  const pending = aggregator.process(micEvent('豆包。'))
  assert.equal(await aggregator.process(micEvent('给我两组')), null)
  assert.equal(await aggregator.process(micEvent('钻石。')), null)

  scheduler.runNext(500)
  const result = await pending

  assert.equal(result.text, '豆包 给我两组 钻石')
  assert.equal(result.metadata.transcriptAggregation, 'wake-followup')
  assert.equal(result.metadata.transcriptFragmentCount, 2)
})

test('drops a standalone wake phrase when the hard wait expires without content', async () => {
  const scheduler = createScheduler()
  const aggregator = createMicTranscriptAggregator({
    wakePhrases: ['豆包'],
    wakeFollowupMaxWaitMs: 8000,
    schedule: scheduler.schedule,
    cancelSchedule: scheduler.cancelSchedule
  })

  const pending = aggregator.process(micEvent('豆包'))
  scheduler.runNext(8000)

  assert.equal(await pending, null)
})

test('routes collected content at the hard deadline when fragments keep arriving', async () => {
  const scheduler = createScheduler()
  const aggregator = createMicTranscriptAggregator({
    wakePhrases: ['豆包'],
    wakeFollowupSilenceMs: 500,
    wakeFollowupMaxWaitMs: 8000,
    schedule: scheduler.schedule,
    cancelSchedule: scheduler.cancelSchedule
  })

  const pending = aggregator.process(micEvent('豆包'))
  await aggregator.process(micEvent('给我钻石'))
  scheduler.runNext(8000)

  assert.equal((await pending).text, '豆包 给我钻石')
})

test('keeps pending follow-up buffers isolated by speaker', async () => {
  const scheduler = createScheduler()
  const aggregator = createMicTranscriptAggregator({
    wakePhrases: ['豆包'],
    wakeFollowupSilenceMs: 500,
    schedule: scheduler.schedule,
    cancelSchedule: scheduler.cancelSchedule
  })

  const first = aggregator.process(micEvent('豆包', 'first'))
  const second = aggregator.process(micEvent('豆包', 'second'))
  await aggregator.process(micEvent('跟着我', 'first'))
  scheduler.runNext(500)

  assert.equal((await first).text, '豆包 跟着我')
  aggregator.clear()
  assert.equal(await second, null)
})

test('routes adjacent Chinese wake prefix without requiring punctuation or whitespace', () => {
  const gate = createMicWakeGate({ wakePhrases: ['豆包'] })
  const result = gate.process('豆包给我两组钻石')

  assert.equal(result.accepted, true)
  assert.equal(result.reason, 'wake-phrase')
  assert.equal(result.text, '给我两组钻石')
})

test('does not treat an English word prefix as a wake phrase', () => {
  const gate = createMicWakeGate({ wakePhrases: ['hey'] })
  const result = gate.process('heyday')

  assert.equal(result.accepted, false)
  assert.equal(result.reason, 'ambient')
})

test('VoiceRuntime routes one merged event and preserves wake-followup metadata', async () => {
  const scheduler = createScheduler()
  const aggregator = createMicTranscriptAggregator({
    wakePhrases: ['豆包'],
    wakeFollowupSilenceMs: 500,
    schedule: scheduler.schedule,
    cancelSchedule: scheduler.cancelSchedule
  })
  const routed = []
  const runtime = new VoiceRuntime({
    enabled: true,
    micTranscriptAggregator: aggregator,
    router: async (event) => {
      routed.push(event)
      return createConversationIntent(event.text)
    },
    onIntent: async () => {}
  })

  const pending = runtime.handleTranscript(micEvent('豆包'))
  assert.equal(await runtime.handleTranscript(micEvent('给我两组钻石')), null)
  scheduler.runNext(500)
  const intent = await pending

  assert.equal(intent.payload, '豆包 给我两组钻石')
  assert.equal(routed.length, 1)
  assert.equal(routed[0].metadata.transcriptAggregation, 'wake-followup')
})
