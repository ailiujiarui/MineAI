import test from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'

import { createClientBridgeRuntime } from '../../src/clientBridge/bridgeRuntime.js'

function canConnect(port: number) {
  return new Promise<boolean>((resolve) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => {
      socket.destroy()
      resolve(true)
    })
    socket.once('error', () => resolve(false))
  })
}

test('bridge runtime starts a listening bridge server on the requested host and port', async () => {
  const runtime = createClientBridgeRuntime()

  try {
    await runtime.start({ host: '127.0.0.1', port: 0 })
    const address = runtime.address()

    assert.equal(address.address, '127.0.0.1')
    assert.equal(address.port > 0, true)
    assert.equal(await canConnect(address.port), true)
  } finally {
    await runtime.stop()
  }
})

test('bridge runtime reports whether it is listening', async () => {
  const runtime = createClientBridgeRuntime()
  assert.equal(runtime.isRunning(), false)

  await runtime.start({ host: '127.0.0.1', port: 0 })
  assert.equal(runtime.isRunning(), true)

  await runtime.stop()
  assert.equal(runtime.isRunning(), false)
})
