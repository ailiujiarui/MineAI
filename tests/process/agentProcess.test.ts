import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { AgentProcess } from '../../src/process/agent_process.js'

class FakeChild extends EventEmitter {
  kills = []
  kill(signal) { this.kills.push(signal) }
}

test('forceRestart creates exactly one successor', () => {
  const children = []
  const manager = new AgentProcess('MineAIZH', 8080, {
    spawnImpl: () => { const child = new FakeChild(); children.push(child); return child },
    minRestartUptimeMs: 0
  })
  manager.start(false, null, 0)
  manager.forceRestart()
  manager.forceRestart()
  assert.deepEqual(children[0].kills, ['SIGINT'])
  children[0].emit('exit', 0, 'SIGINT')
  assert.equal(children.length, 2)
})

test('explicit stop never restarts the agent', () => {
  const children = []
  const manager = new AgentProcess('MineAIZH', 8080, {
    spawnImpl: () => { const child = new FakeChild(); children.push(child); return child },
    minRestartUptimeMs: 0
  })
  manager.start()
  manager.stop()
  children[0].emit('exit', 0, 'SIGINT')
  assert.equal(children.length, 1)
  assert.equal(manager.running, false)
})

test('agent process receives its MindServer authentication token without command-line exposure', () => {
  let spawnOptions
  const manager = new AgentProcess('MineAIZH', 8080, {
    authToken: 'agent-secret-token',
    spawnImpl: (_command, _args, options) => {
      spawnOptions = options
      return new FakeChild()
    }
  })
  manager.start()
  assert.equal(spawnOptions.env.MINDCRAFT_AGENT_TOKEN, 'agent-secret-token')
})

test('agent process rotates its authentication token for every generation', () => {
  const tokens = []
  let nextToken = 0
  const children = []
  const manager = new AgentProcess('MineAIZH', 8080, {
    authTokenProvider: () => `generation-${++nextToken}`,
    spawnImpl: (_command, _args, options) => {
      tokens.push(options.env.MINDCRAFT_AGENT_TOKEN)
      const child = new FakeChild()
      children.push(child)
      return child
    }
  })
  manager.start()
  manager.forceRestart()
  children[0].emit('exit', 0, 'SIGINT')
  assert.deepEqual(tokens, ['generation-1', 'generation-2'])
})

test('all child generations inherit one run root and restart metadata stays out of init_message', () => {
  const spawns = []
  const children = []
  const manager = new AgentProcess('MineAIZH', 8080, {
    runRoot: 'D:\\ximu\\MineAI\\runs\\lifecycle-test',
    spawnImpl: (_command, args, options) => {
      spawns.push({ args, env: options.env })
      const child = new FakeChild()
      children.push(child)
      return child
    },
    minRestartUptimeMs: 0
  })

  manager.start(false, '玩家配置的首条消息', 0)
  manager.forceRestart()
  children[0].emit('exit', 0, 'SIGINT')

  assert.equal(spawns.length, 2)
  assert.equal(spawns[0].env.MINDCRAFT_RUN_ROOT, spawns[1].env.MINDCRAFT_RUN_ROOT)
  assert.equal(spawns[0].args[spawns[0].args.indexOf('-m') + 1], '玩家配置的首条消息')
  assert.equal(spawns[1].args.includes('-m'), false)
  assert.equal(spawns[1].args[spawns[1].args.indexOf('--restart-cause') + 1], 'planned')
})

test('unexpected child exit uses internal restart cause without replaying user init_message', () => {
  const spawnArgs = []
  const children = []
  const manager = new AgentProcess('MineAIZH', 8080, {
    runRoot: 'D:\\ximu\\MineAI\\runs\\crash-test',
    spawnImpl: (_command, args) => {
      spawnArgs.push(args)
      const child = new FakeChild()
      children.push(child)
      return child
    },
    minRestartUptimeMs: 0
  })

  manager.start(false, '只应发送一次', 0)
  children[0].emit('exit', 1, null)

  assert.equal(spawnArgs.length, 2)
  assert.equal(spawnArgs[1].includes('-m'), false)
  assert.equal(spawnArgs[1][spawnArgs[1].indexOf('--restart-cause') + 1], 'unexpected-exit:1:none')
})
