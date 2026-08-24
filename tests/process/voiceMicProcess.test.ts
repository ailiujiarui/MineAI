import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { VoiceMicProcess } from '../../src/process/voiceMicProcess.js'

class FakeChild extends EventEmitter {
  stdin = { destroyed: false, writes: [], write: (value) => this.stdin.writes.push(value), end: () => queueMicrotask(() => this.emit('exit', 0, null)) }
  killed = false
  kill() { this.killed = true; queueMicrotask(() => this.emit('exit', 0, null)) }
}

test('voice mic process waits for readiness and starts once', () => {
  const children = []
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: (_command, args) => { const child = new FakeChild(); children.push(args); return child }
  })
  manager.start()
  assert.equal(children.length, 0)
  manager.setReady(true)
  manager.start()
  assert.equal(children.length, 1)
  manager.start()
  assert.equal(children.length, 1)
})

test('voice mic process authenticates with the current Agent generation token', () => {
  let spawnOptions
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    authTokenProvider: () => 'current-agent-token',
    spawnImpl: (_command, _args, options) => {
      spawnOptions = options
      return new FakeChild()
    }
  })
  manager.start()
  manager.setReady(true)
  assert.equal(spawnOptions.env.MINDCRAFT_AGENT_NAME, 'MineAIZH')
  assert.equal(spawnOptions.env.MINDCRAFT_AGENT_TOKEN, 'current-agent-token')
  assert.equal(spawnOptions.env.PYTHONUNBUFFERED, '1')
})

test('voice mic process stops cooperatively and does not restart', async () => {
  let child
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: () => (child = new FakeChild()),
    stopTimeoutMs: 50,
    restartBackoffMs: 1
  })
  manager.start()
  manager.setReady(true)
  await manager.stop()
  assert.deepEqual(child.stdin.writes, ['stop\n'])
  assert.equal(child.killed, false)
})

test('temporary agent unready stops the child and starts again on later readiness', async () => {
  const children = []
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: () => { const child = new FakeChild(); children.push(child); return child },
    stopTimeoutMs: 50
  })
  manager.start()
  manager.setReady(true)
  await manager.setReady(false)
  assert.equal(children.length, 1)
  await manager.setReady(true)
  assert.equal(children.length, 2)
  await manager.stop()
})

test('unexpected exit restarts only while ready and desired', async () => {
  const children = []
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: () => { const child = new FakeChild(); children.push(child); return child },
    restartBackoffMs: 1,
    restartLimit: 1
  })
  manager.start()
  manager.setReady(true)
  children[0].emit('exit', 1, null)
  await new Promise((resolve) => setTimeout(resolve, 10))
  assert.equal(children.length, 2)
  await manager.stop()
  children[1].emit('exit', 1, null)
  await new Promise((resolve) => setTimeout(resolve, 10))
  assert.equal(children.length, 2)
})

test('spawn error without exit schedules one restart and stale exit is ignored', () => {
  const children = []
  const timers = []
  const states = []
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: () => { const child = new FakeChild(); children.push(child); return child },
    setTimeout: (callback, delay) => { timers.push({ callback, delay }); return timers.length },
    clearTimeout: () => {},
    restartBackoffMs: 25,
    onState: state => states.push(state)
  })
  manager.start()
  manager.setReady(true)
  children[0].emit('error', new Error('spawn failed'))
  children[0].emit('exit', 1, null)
  assert.equal(timers.length, 1)
  assert.equal(timers[0].delay, 25)
  assert.equal(states.filter(state => state.state === 'error').length, 1)
  timers[0].callback()
  assert.equal(children.length, 2)
})

test('restart exhaustion emits an explicit state', () => {
  const states = []
  const manager = new VoiceMicProcess({
    agent: 'MineAIZH',
    pythonCommand: 'python',
    spawnImpl: () => new FakeChild(),
    restartLimit: 0,
    onState: state => states.push(state)
  })
  manager.start()
  manager.setReady(true)
  manager.child.emit('exit', 1, null)
  assert.equal(states.at(-1).state, 'restart-exhausted')
})
