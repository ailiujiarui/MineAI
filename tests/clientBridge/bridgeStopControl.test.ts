import test from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'

import { requestBridgeStopAll } from '../../src/clientBridge/bridgeControlClient.js'
import { createClientBridgeServer } from '../../src/clientBridge/bridgeServer.js'
import { createAckMessage, createHelloMessage, createSnapshotMessage } from '../../src/clientBridge/clientProtocol.js'

function connect(port: number) {
  return new Promise<net.Socket>((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => resolve(socket))
    socket.once('error', reject)
  })
}

function send(socket: net.Socket, value: unknown) {
  socket.write(`${JSON.stringify(value)}\n`)
}

function read(socket: net.Socket) {
  return new Promise<any>((resolve, reject) => {
    let buffer = ''
    const onData = (chunk: Buffer) => {
      buffer += chunk.toString('utf8')
      const newline = buffer.indexOf('\n')
      if (newline < 0) return
      socket.off('data', onData)
      resolve(JSON.parse(buffer.slice(0, newline)))
    }
    socket.on('data', onData)
    socket.once('error', reject)
  })
}

function announceReady(socket: net.Socket, clientId: string, playerName: string) {
  send(socket, createHelloMessage({
    clientId,
    minecraftVersion: '1.20.1',
    loader: 'forge',
    modCapabilities: { epicFight: false, slashBlade: true }
  }))
  send(socket, createSnapshotMessage({
    tick: 1,
    player: {
      name: playerName,
      health: 20,
      food: 20,
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      combatMode: 'vanilla'
    },
    nearbyEntities: []
  }))
}

test('strict admin stop selects a fresh client and returns its matching ack', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const socket = await connect(bridge.address().port)

  try {
    announceReady(socket, 'forge-stop-1', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))

    const commandPromise = read(socket)
    const resultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'request-1',
      targetPlayerName: 'pih',
      ackTimeoutMs: 500
    })
    const command = await commandPromise

    assert.deepEqual(command.payload.actions, [{ kind: 'stop_all' }])
    assert.match(command.payload.id, /^stop-[0-9a-f-]+$/)
    send(socket, createAckMessage({ commandId: command.payload.id, status: 'ok', detail: 'accepted' }))

    const result = await resultPromise
    assert.equal(result.status, 'ok')
    assert.equal(result.clientId, 'forge-stop-1')
    assert.equal(result.ack.commandId, command.payload.id)
  } finally {
    socket.destroy()
    await bridge.close()
  }
})

test('strict admin stop fails closed with no fresh client or ambiguous clients', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const first = await connect(bridge.address().port)
  const second = await connect(bridge.address().port)

  try {
    let result = await requestBridgeStopAll({ port: bridge.address().port, requestId: 'none' })
    assert.equal(result.status, 'unavailable')

    announceReady(first, 'forge-a', 'same-player')
    announceReady(second, 'forge-b', 'same-player')
    await new Promise((resolve) => setTimeout(resolve, 20))

    result = await requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'ambiguous',
      targetPlayerName: 'same-player'
    })
    assert.equal(result.status, 'rejected')
    assert.match(result.message, /Multiple matching Forge clients/)
  } finally {
    first.destroy()
    second.destroy()
    await bridge.close()
  }
})

test('strict admin stop maps error ack and timeout without treating them as success', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const socket = await connect(bridge.address().port)

  try {
    announceReady(socket, 'forge-error', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))

    let commandPromise = read(socket)
    let resultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'error-ack',
      ackTimeoutMs: 500
    })
    let command = await commandPromise
    send(socket, createAckMessage({ commandId: command.payload.id, status: 'error', detail: 'rejected action' }))
    let result = await resultPromise
    assert.equal(result.status, 'rejected')
    assert.equal(result.ack.detail, 'rejected action')

    await new Promise((resolve) => setTimeout(resolve, 300))
    commandPromise = read(socket)
    resultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'timeout',
      ackTimeoutMs: 30
    })
    command = await commandPromise
    assert.match(command.payload.id, /^stop-[0-9a-f-]+$/)
    result = await resultPromise
    assert.equal(result.status, 'timeout')
  } finally {
    socket.destroy()
    await bridge.close()
  }
})

test('strict admin stop rejects malformed requests without forwarding a command', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const forge = await connect(bridge.address().port)
  const admin = await connect(bridge.address().port)

  try {
    announceReady(forge, 'forge-malformed', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const responsePromise = read(admin)
    send(admin, { type: 'admin_stop_all', payload: { requestId: '', ackTimeoutMs: -1 } })
    const response = await responsePromise
    assert.equal(response.payload.status, 'rejected')
    assert.equal(bridge.getClient('forge-malformed')?.lastAck, null)
  } finally {
    forge.destroy()
    admin.destroy()
    await bridge.close()
  }
})

test('strict admin stop refuses non-loopback bridge hosts', async () => {
  await assert.rejects(
    requestBridgeStopAll({ host: '192.0.2.10', requestId: 'remote-denied' }),
    /bridge host must be loopback/
  )
})

test('strict admin stop coalesces concurrent requests for the same Forge client', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const socket = await connect(bridge.address().port)
  try {
    announceReady(socket, 'forge-coalesced', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const commandPromise = read(socket)
    const first = requestBridgeStopAll({ port: bridge.address().port, requestId: 'coalesce-a', ackTimeoutMs: 500 })
    const second = requestBridgeStopAll({ port: bridge.address().port, requestId: 'coalesce-b', ackTimeoutMs: 500 })
    const command = await commandPromise
    send(socket, createAckMessage({ commandId: command.payload.id, status: 'ok', detail: 'accepted' }))
    const results = await Promise.all([first, second])

    assert.deepEqual(results.map((result) => result.status), ['ok', 'ok'])
    assert.equal(results.some((result) => result.coalesced === true), true)
    assert.equal(results[0].commandId, results[1].commandId)

    const rateLimited = await requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'coalesce-rate-limited',
      ackTimeoutMs: 500
    })
    assert.equal(rateLimited.status, 'rejected')
    assert.match(rateLimited.message, /rate limit/i)
  } finally {
    socket.destroy()
    await bridge.close()
  }
})

test('strict admin stop reports disconnect when the selected client is replaced before ack', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const first = await connect(bridge.address().port)
  let replacement: net.Socket | null = null
  try {
    announceReady(first, 'forge-replaced', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const commandPromise = read(first)
    const resultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'replacement',
      ackTimeoutMs: 500
    })
    await commandPromise

    replacement = await connect(bridge.address().port)
    announceReady(replacement, 'forge-replaced', 'pih')
    const result = await resultPromise
    assert.equal(result.status, 'disconnected')

    await new Promise((resolve) => setTimeout(resolve, 20))
    const replacementCommandPromise = read(replacement)
    const replacementResultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'replacement-retry',
      ackTimeoutMs: 500
    })
    const replacementCommand = await replacementCommandPromise
    send(replacement, createAckMessage({
      commandId: replacementCommand.payload.id,
      status: 'ok',
      detail: 'accepted'
    }))
    assert.equal((await replacementResultPromise).status, 'ok')
  } finally {
    first.destroy()
    replacement?.destroy()
    await bridge.close()
  }
})

test('strict admin stop uses a new command id when a caller reuses its request id', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const socket = await connect(bridge.address().port)
  try {
    announceReady(socket, 'forge-request-replay', 'pih')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const firstCommandPromise = read(socket)
    const firstResultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'reused-request',
      ackTimeoutMs: 50
    })
    const firstCommand = await firstCommandPromise
    assert.equal((await firstResultPromise).status, 'timeout')

    await new Promise((resolve) => setTimeout(resolve, 260))
    const secondCommandPromise = read(socket)
    const secondResultPromise = requestBridgeStopAll({
      port: bridge.address().port,
      requestId: 'reused-request',
      ackTimeoutMs: 500
    })
    const secondCommand = await secondCommandPromise
    assert.notEqual(secondCommand.payload.id, firstCommand.payload.id)

    send(socket, createAckMessage({ commandId: firstCommand.payload.id, status: 'ok', detail: 'late' }))
    const early = await Promise.race([
      secondResultPromise.then(() => 'settled'),
      new Promise((resolve) => setTimeout(() => resolve('pending'), 30))
    ])
    assert.equal(early, 'pending')

    send(socket, createAckMessage({ commandId: secondCommand.payload.id, status: 'ok', detail: 'accepted' }))
    assert.equal((await secondResultPromise).status, 'ok')
  } finally {
    socket.destroy()
    await bridge.close()
  }
})
