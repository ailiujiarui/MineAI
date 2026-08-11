import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { waitForMindServerListening } from '../../src/mindcraft/mindserver.js'

class FakeServer extends EventEmitter {
  listening = false
}

test('MindServer readiness waits for the listening event', async () => {
  const server = new FakeServer()
  let resolved = false
  const ready = waitForMindServerListening(server).then(() => { resolved = true })
  await Promise.resolve()
  assert.equal(resolved, false)
  server.listening = true
  server.emit('listening')
  await ready
  assert.equal(resolved, true)
})

test('MindServer readiness rejects bind errors', async () => {
  const server = new FakeServer()
  const ready = waitForMindServerListening(server)
  server.emit('error', new Error('address in use'))
  await assert.rejects(ready, /address in use/)
})
