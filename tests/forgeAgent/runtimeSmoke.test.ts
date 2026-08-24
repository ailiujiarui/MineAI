import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'

import { createAckMessage, createHelloMessage, createSnapshotMessage } from '../../src/clientBridge/clientProtocol.js'
import {
  inspectForgeRuntimePrerequisites,
  runForgeRuntimeSmoke
} from '../../src/forgeAgent/runtimeSmoke.js'

async function createInstance() {
  const instanceDir = await mkdtemp(path.join(os.tmpdir(), 'forge-runtime-smoke-'))
  const modsDir = path.join(instanceDir, 'mods')
  await mkdir(modsDir)
  await writeFile(path.join(modsDir, 'gameai_forge_agent-0.1.0.jar'), Buffer.from('test-jar'))
  return instanceDir
}

function connect(port: number) {
  return new Promise<net.Socket>((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => resolve(socket))
    socket.once('error', reject)
  })
}

function sendJsonLine(socket: net.Socket, value: unknown) {
  socket.write(`${JSON.stringify(value)}\n`)
}

function sendReadyState(socket: net.Socket, clientId: string, minecraftVersion = '1.20.1') {
  sendJsonLine(socket, createHelloMessage({
    clientId,
    minecraftVersion,
    loader: 'forge',
    modCapabilities: { epicFight: true, slashBlade: true }
  }))
  sendJsonLine(socket, createSnapshotMessage({
    tick: 42,
    player: {
      name: 'SmokePlayer',
      health: 20,
      food: 20,
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      combatMode: 'epicfight'
    },
    nearbyEntities: []
  }))
}

function readJsonLine(socket: net.Socket) {
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

test('forge runtime preflight validates the instance, mods directory, jar, and Node version', async () => {
  const instanceDir = await createInstance()
  try {
    const result = await inspectForgeRuntimePrerequisites({ instanceDir, nodeVersion: '20.12.0' })
    assert.equal(result.instanceDir, instanceDir)
    assert.equal(path.basename(result.jarPath), 'gameai_forge_agent-0.1.0.jar')

    await assert.rejects(
      inspectForgeRuntimePrerequisites({ instanceDir, jarName: 'missing.jar' }),
      /Required file does not exist.*missing\.jar/
    )
    await assert.rejects(
      inspectForgeRuntimePrerequisites({ instanceDir, nodeVersion: '16.20.0' }),
      /Node\.js 18 or newer is required/
    )
  } finally {
    await rm(instanceDir, { recursive: true, force: true })
  }
})

test('forge runtime smoke waits for hello and a fresh snapshot', async () => {
  const instanceDir = await createInstance()
  let fakeClient: Promise<void> = Promise.resolve()

  try {
    const result = await runForgeRuntimeSmoke({
      instanceDir,
      port: 0,
      connectTimeoutMs: 1_000,
      onListening({ port }) {
        fakeClient = (async () => {
          const socket = await connect(port)
          sendReadyState(socket, 'forge-smoke-ready')
          await new Promise<void>((resolve) => socket.once('close', () => resolve()))
        })()
      }
    })

    assert.equal(result.client.clientId, 'forge-smoke-ready')
    assert.equal(result.client.snapshot?.tick, 42)
    assert.equal(result.ack, null)
    await fakeClient
  } finally {
    await rm(instanceDir, { recursive: true, force: true })
  }
})

test('forge runtime smoke sends stop_all only when explicitly requested and waits for its ack', async () => {
  const instanceDir = await createInstance()
  let fakeClient: Promise<void> = Promise.resolve()

  try {
    const result = await runForgeRuntimeSmoke({
      instanceDir,
      port: 0,
      command: 'stop_all',
      connectTimeoutMs: 1_000,
      ackTimeoutMs: 1_000,
      onListening({ port }) {
        fakeClient = (async () => {
          const socket = await connect(port)
          sendReadyState(socket, 'forge-smoke-ack')
          const command = await readJsonLine(socket)
          assert.deepEqual(command.payload.actions, [{ kind: 'stop_all' }])
          sendJsonLine(socket, createAckMessage({ commandId: command.payload.id, status: 'ok', detail: 'accepted' }))
          await new Promise<void>((resolve) => socket.once('close', () => resolve()))
        })()
      }
    })

    assert.equal(result.ack?.status, 'ok')
    assert.match(result.ack?.commandId || '', /^smoke-stop-/)
    await fakeClient
  } finally {
    await rm(instanceDir, { recursive: true, force: true })
  }
})

test('forge runtime smoke times out cleanly without a client', async () => {
  const instanceDir = await createInstance()
  try {
    await assert.rejects(
      runForgeRuntimeSmoke({ instanceDir, port: 0, connectTimeoutMs: 30 }),
      /Timed out after 30ms waiting for Forge runtime readiness/
    )
  } finally {
    await rm(instanceDir, { recursive: true, force: true })
  }
})

test('forge runtime smoke rejects a Forge hello from the wrong Minecraft version', async () => {
  const instanceDir = await createInstance()
  let socket: net.Socket | null = null
  try {
    await assert.rejects(
      runForgeRuntimeSmoke({
        instanceDir,
        port: 0,
        connectTimeoutMs: 1_000,
        onListening({ port }) {
          void connect(port).then((connected) => {
            socket = connected
            sendReadyState(connected, 'forge-wrong-version', '1.19.4')
          })
        }
      }),
      /uses Minecraft 1\.19\.4; expected 1\.20\.1/
    )
  } finally {
    socket?.destroy()
    await rm(instanceDir, { recursive: true, force: true })
  }
})

test('forge runtime smoke refuses to replace a bridge already owning the port', async () => {
  const instanceDir = await createInstance()
  const occupied = net.createServer()
  await new Promise<void>((resolve) => occupied.listen(0, '127.0.0.1', resolve))
  const address = occupied.address()
  assert.ok(address && typeof address !== 'string')

  try {
    await assert.rejects(
      runForgeRuntimeSmoke({ instanceDir, port: address.port, connectTimeoutMs: 30 }),
      (error: any) => error?.code === 'EADDRINUSE'
    )
  } finally {
    await new Promise<void>((resolve, reject) => occupied.close((error) => error ? reject(error) : resolve()))
    await rm(instanceDir, { recursive: true, force: true })
  }
})
