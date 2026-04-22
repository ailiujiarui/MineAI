import test from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'

import { createCommandMessage, createHelloMessage, createSnapshotMessage } from '../../src/clientBridge/clientProtocol.js'
import { createAckMessage } from '../../src/clientBridge/clientProtocol.js'
import { createClientBridgeServer } from '../../src/clientBridge/bridgeServer.js'

function connectClient(port: number) {
  return new Promise<net.Socket>((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => resolve(socket))
    socket.once('error', reject)
  })
}

function readJsonLine(socket: net.Socket) {
  return new Promise<any>((resolve, reject) => {
    let buffer = ''

    const onData = (chunk: Buffer) => {
      buffer += chunk.toString('utf8')
      const newline = buffer.indexOf('\n')
      if (newline >= 0) {
        socket.off('data', onData)
        resolve(JSON.parse(buffer.slice(0, newline)))
      }
    }

    socket.on('data', onData)
    socket.once('error', reject)
  })
}

function sendJsonLine(socket: net.Socket, payload: unknown) {
  socket.write(`${JSON.stringify(payload)}\n`)
}

test('bridge server accepts hello messages and tracks connected clients', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const socket = await connectClient(address.port)

    sendJsonLine(socket, createHelloMessage({
      clientId: 'forge-agent-1',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: true,
        slashBlade: false
      }
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    const client = bridge.getClient('forge-agent-1')

    assert.equal(client?.hello?.payload.loader, 'forge')
    assert.equal(bridge.listClients().length, 1)

    socket.destroy()
  } finally {
    await bridge.close()
  }
})

test('bridge server stores latest snapshots per client', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const socket = await connectClient(address.port)

    sendJsonLine(socket, createHelloMessage({
      clientId: 'forge-agent-2',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: true,
        slashBlade: true
      }
    }))
    sendJsonLine(socket, createSnapshotMessage({
      tick: 99,
      player: {
        name: 'BotPilot',
        health: 17,
        food: 18,
        position: { x: 10, y: 70, z: -2 },
        combatMode: 'epicfight'
      }
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    const client = bridge.getClient('forge-agent-2')

    assert.equal(client?.snapshot?.payload.tick, 99)
    assert.equal(client?.snapshot?.payload.player.combatMode, 'epicfight')

    socket.destroy()
  } finally {
    await bridge.close()
  }
})

test('bridge server stores the latest ack per client', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const socket = await connectClient(address.port)

    sendJsonLine(socket, createHelloMessage({
      clientId: 'forge-agent-ack',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: false,
        slashBlade: false
      }
    }))
    sendJsonLine(socket, createAckMessage({
      commandId: 'cmd-ack-1',
      status: 'ok',
      detail: 'executed'
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    const client = bridge.getClient('forge-agent-ack')

    assert.equal(client?.lastAck?.payload.commandId, 'cmd-ack-1')
    assert.equal(client?.lastAck?.payload.status, 'ok')
    assert.equal(typeof client?.lastAckAt, 'number')

    socket.destroy()
  } finally {
    await bridge.close()
  }
})

test('bridge server can send structured command messages back to a connected client', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const socket = await connectClient(address.port)

    sendJsonLine(socket, createHelloMessage({
      clientId: 'forge-agent-3',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: false,
        slashBlade: false
      }
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))

    const outbound = readJsonLine(socket)
    await bridge.sendCommand('forge-agent-3', createCommandMessage({
      id: 'cmd-1',
      actions: [
        { kind: 'look', yaw: 45, pitch: 5 },
        { kind: 'attack', mode: 'tap', targetEntityId: 42 }
      ]
    }))

    const received = await outbound
    assert.equal(received.type, 'command')
    assert.equal(received.payload.actions[1].targetEntityId, 42)

    socket.destroy()
  } finally {
    await bridge.close()
  }
})

test('bridge server can answer admin list requests with connected clients', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const clientSocket = await connectClient(address.port)
    const adminSocket = await connectClient(address.port)

    sendJsonLine(clientSocket, createHelloMessage({
      clientId: 'forge-agent-list',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: true,
        slashBlade: false
      }
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    const responsePromise = readJsonLine(adminSocket)
    sendJsonLine(adminSocket, { type: 'admin_list_clients' })
    const response = await responsePromise

    assert.equal(response.type, 'admin_clients')
    assert.equal(response.payload.clients.length, 1)
    assert.equal(response.payload.clients[0].clientId, 'forge-agent-list')
    assert.equal(typeof response.payload.clients[0].connectedAt, 'number')
    assert.equal(response.payload.clients[0].lastAck, null)

    clientSocket.destroy()
    adminSocket.destroy()
  } finally {
    await bridge.close()
  }
})

test('bridge server accepts admin command envelopes and forwards them to target clients', async () => {
  const bridge = createClientBridgeServer()
  await bridge.listen(0)

  try {
    const address = bridge.address()
    const clientSocket = await connectClient(address.port)
    const adminSocket = await connectClient(address.port)

    sendJsonLine(clientSocket, createHelloMessage({
      clientId: 'forge-agent-admin',
      minecraftVersion: '1.20.1',
      loader: 'forge',
      modCapabilities: {
        epicFight: false,
        slashBlade: false
      }
    }))

    await new Promise((resolve) => setTimeout(resolve, 50))

    const outbound = readJsonLine(clientSocket)
    sendJsonLine(adminSocket, {
      type: 'admin_command',
      payload: {
        clientId: 'forge-agent-admin',
        command: createCommandMessage({
          id: 'cmd-admin',
          actions: [{ kind: 'stop_all' }]
        })
      }
    })

    const forwarded = await outbound
    assert.equal(forwarded.type, 'command')
    assert.equal(forwarded.payload.id, 'cmd-admin')

    clientSocket.destroy()
    adminSocket.destroy()
  } finally {
    await bridge.close()
  }
})
