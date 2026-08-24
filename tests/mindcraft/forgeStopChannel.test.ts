import test from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'
import { io as connectSocket } from 'socket.io-client'

import { createClientBridgeServer } from '../../src/clientBridge/bridgeServer.js'
import { createAckMessage, createHelloMessage, createSnapshotMessage } from '../../src/clientBridge/clientProtocol.js'
import {
  closeMindServer,
  createMindServer,
  registerAgent,
  waitForMindServerListening
} from '../../src/mindcraft/mindserver.js'

function connectForge(port: number) {
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

test('MindServer forwards a runtime-ready Agent stop request to the strict bridge channel', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)
  const forge = await connectForge(bridge.address().port)
  const mindServer = createMindServer(false, 0)
  await waitForMindServerListening(mindServer)
  const mindAddress = mindServer.address()
  assert.ok(mindAddress && typeof mindAddress !== 'string')

  send(forge, createHelloMessage({
    clientId: 'forge-mindserver-stop',
    minecraftVersion: '1.20.1',
    loader: 'forge',
    modCapabilities: { epicFight: false, slashBlade: true }
  }))
  send(forge, createSnapshotMessage({
    tick: 7,
    player: {
      name: 'pih',
      health: 20,
      food: 20,
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      combatMode: 'vanilla'
    },
    nearbyEntities: []
  }))

  const authToken = registerAgent({
    profile: { name: 'MineAIZH-stop-test' },
    forge_action: {
      enabled: true,
      host: '127.0.0.1',
      port: bridge.address().port,
      target_player_name: 'pih',
      snapshot_freshness_ms: 2000,
      ack_timeout_ms: 500
    }
  }, 3000)

  const socket = connectSocket(`http://localhost:${mindAddress.port}`, {
    auth: { agentName: 'MineAIZH-stop-test', token: authToken },
    autoConnect: false
  })
  const micSocket = connectSocket(`http://localhost:${mindAddress.port}`, {
    auth: { agentName: 'MineAIZH-stop-test', token: authToken },
    autoConnect: false
  })
  const impostor = connectSocket(`http://localhost:${mindAddress.port}`)
  try {
    await new Promise<void>((resolve, reject) => {
      impostor.once('connect', () => resolve())
      impostor.once('connect_error', reject)
    })
    impostor.emit('login-agent', 'MineAIZH-stop-test')
    impostor.emit('agent-runtime-ready', 'MineAIZH-stop-test')
    const denied = await new Promise<any>((resolve) => {
      impostor.emit('forge-stop-request', { speakerId: 'impostor', origin: 'remote' }, resolve)
    })
    socket.connect()
    assert.equal(denied.status, 'rejected')

    await new Promise<void>((resolve, reject) => {
      socket.once('connect', () => resolve())
      socket.once('connect_error', reject)
    })
    socket.emit('login-agent', 'MineAIZH-stop-test')
    socket.emit('agent-runtime-ready', 'MineAIZH-stop-test')
    await new Promise((resolve) => setTimeout(resolve, 20))

    let forgedTranscriptDelivered = false
    socket.once('voice-transcript', () => { forgedTranscriptDelivered = true })
    impostor.emit('voice-transcript', 'MineAIZH-stop-test', {
      text: '停止',
      speakerId: 'impostor',
      source: 'mic'
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    assert.equal(forgedTranscriptDelivered, false)

    micSocket.connect()
    await new Promise<void>((resolve, reject) => {
      micSocket.once('connect', () => resolve())
      micSocket.once('connect_error', reject)
    })
    const authenticatedTranscript = new Promise<any>((resolve) => socket.once('voice-transcript', resolve))
    micSocket.emit('voice-transcript', 'MineAIZH-stop-test', {
      text: '你好',
      speakerId: 'mic_user',
      source: 'mic'
    })
    assert.equal((await authenticatedTranscript).text, '你好')

    const commandPromise = read(forge)
    const resultPromise = new Promise<any>((resolve) => {
      socket.emit('forge-stop-request', { speakerId: 'mic_user', origin: 'mic' }, resolve)
    })
    const command = await commandPromise
    assert.deepEqual(command.payload.actions, [{ kind: 'stop_all' }])
    send(forge, createAckMessage({ commandId: command.payload.id, status: 'ok', detail: 'accepted' }))

    const result = await resultPromise
    assert.equal(result.status, 'ok')
    assert.equal(result.clientId, 'forge-mindserver-stop')

    const rateLimited = await new Promise<any>((resolve) => {
      socket.emit('forge-stop-request', { speakerId: 'mic_user', origin: 'mic' }, resolve)
    })
    assert.equal(rateLimited.status, 'rejected')
    assert.match(rateLimited.message, /Agent Forge stop rate limit/i)
  } finally {
    impostor.close()
    micSocket.close()
    socket.close()
    forge.destroy()
    await closeMindServer()
    await bridge.close()
  }
})

test('a delayed disconnect from an old Agent socket does not clear its replacement', async () => {
  const mindServer = createMindServer(false, 0)
  await waitForMindServerListening(mindServer)
  const mindAddress = mindServer.address()
  assert.ok(mindAddress && typeof mindAddress !== 'string')

  const agentName = 'MineAIZH-restart-test'
  const authToken = registerAgent({
    profile: { name: agentName },
    forge_action: {
      enabled: true,
      host: '127.0.0.1',
      port: 1,
      ack_timeout_ms: 100
    }
  }, 3001)
  const connectAgent = () => connectSocket(`http://localhost:${mindAddress.port}`, {
    auth: { agentName, token: authToken },
    autoConnect: false
  })
  const oldSocket = connectAgent()
  const replacementSocket = connectAgent()

  try {
    for (const socket of [oldSocket, replacementSocket]) {
      socket.connect()
      await new Promise<void>((resolve, reject) => {
        socket.once('connect', () => resolve())
        socket.once('connect_error', reject)
      })
      socket.emit('login-agent', agentName)
      socket.emit('agent-runtime-ready', agentName)
      await new Promise((resolve) => setTimeout(resolve, 20))
    }

    oldSocket.close()
    await new Promise((resolve) => setTimeout(resolve, 20))

    const result = await new Promise<any>((resolve) => {
      replacementSocket.emit('forge-stop-request', { speakerId: 'restart-test', origin: 'test' }, resolve)
    })
    assert.equal(result.status, 'unavailable')
    assert.notEqual(result.message, 'Agent is not runtime-ready')
  } finally {
    oldSocket.close()
    replacementSocket.close()
    await closeMindServer()
  }
})
