import { stat } from 'node:fs/promises'
import path from 'node:path'

import { createClientBridgeRuntime } from '../clientBridge/bridgeRuntime.js'
import { createStopAllCommand } from '../clientBridge/clientCommander.js'
import { DEFAULT_FORGE_AGENT_JAR_NAME } from './deploySupport.js'

type BridgeRuntime = ReturnType<typeof createClientBridgeRuntime>
type BridgeServer = ReturnType<BridgeRuntime['getServer']>
type BridgeClient = NonNullable<ReturnType<BridgeServer['getClient']>>

export interface ForgeRuntimeSmokeOptions {
    instanceDir: string
    jarName?: string
    host?: string
    port?: number
    clientId?: string
    expectedMinecraftVersion?: string
    connectTimeoutMs?: number
    snapshotFreshnessMs?: number
    ackTimeoutMs?: number
    command?: 'none' | 'stop_all'
    signal?: AbortSignal
    nodeVersion?: string
    onListening?: (address: { address: string; port: number }) => void
}

async function requirePath(targetPath: string, kind: 'file' | 'directory') {
    let details
    try {
        details = await stat(targetPath)
    } catch {
        throw new Error(`Required ${kind} does not exist: ${targetPath}`)
    }

    const valid = kind === 'file' ? details.isFile() : details.isDirectory()
    if (!valid) {
        throw new Error(`Required ${kind} has the wrong type: ${targetPath}`)
    }
}

export async function inspectForgeRuntimePrerequisites(options: Pick<ForgeRuntimeSmokeOptions,
    'instanceDir' | 'jarName' | 'nodeVersion'>) {
    const instanceDir = path.resolve(options.instanceDir)
    const modsDir = path.join(instanceDir, 'mods')
    const jarPath = path.join(modsDir, options.jarName || DEFAULT_FORGE_AGENT_JAR_NAME)
    const nodeVersion = options.nodeVersion || process.versions.node
    const nodeMajor = Number.parseInt(nodeVersion.split('.')[0] || '', 10)

    if (!Number.isFinite(nodeMajor) || nodeMajor < 18) {
        throw new Error(`Node.js 18 or newer is required; found ${nodeVersion}`)
    }

    await requirePath(instanceDir, 'directory')
    await requirePath(modsDir, 'directory')
    await requirePath(jarPath, 'file')

    return { instanceDir, modsDir, jarPath, nodeVersion }
}

function abortError(signal?: AbortSignal) {
    const reason = signal?.reason
    return reason instanceof Error ? reason : new Error('Forge runtime smoke test was aborted')
}

function delay(milliseconds: number, signal?: AbortSignal) {
    if (signal?.aborted) return Promise.reject(abortError(signal))

    return new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
            signal?.removeEventListener('abort', onAbort)
            resolve()
        }, milliseconds)
        const onAbort = () => {
            clearTimeout(timer)
            reject(abortError(signal))
        }
        signal?.addEventListener('abort', onAbort, { once: true })
    })
}

export async function waitForForgeRuntimeClient(
    server: BridgeServer,
    options: {
        clientId?: string
        expectedMinecraftVersion?: string
        timeoutMs?: number
        snapshotFreshnessMs?: number
        signal?: AbortSignal
    } = {}
) {
    const timeoutMs = options.timeoutMs ?? 120_000
    const snapshotFreshnessMs = options.snapshotFreshnessMs ?? 2_000
    const expectedMinecraftVersion = options.expectedMinecraftVersion ?? '1.20.1'
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
        throw new Error(`timeoutMs must be a positive number; received ${timeoutMs}`)
    }
    if (!Number.isFinite(snapshotFreshnessMs) || snapshotFreshnessMs <= 0) {
        throw new Error(`snapshotFreshnessMs must be a positive number; received ${snapshotFreshnessMs}`)
    }
    const deadline = Date.now() + timeoutMs
    let lastState = 'no validated hello received'
    let selectedClient: BridgeClient | null = null

    while (Date.now() <= deadline) {
        if (options.signal?.aborted) throw abortError(options.signal)

        const helloClients = server.listClients().filter((client) => client.hello)
        if (!options.clientId && helloClients.length > 1) {
            throw new Error(`Multiple Forge clients are connected (${helloClients.map((client) => client.id).join(', ')}); specify --client`)
        }

        const client = selectedClient || (options.clientId
            ? server.getClient(options.clientId)
            : helloClients[0] || null)

        if (client?.hello) {
            if (!selectedClient) selectedClient = client
            if (server.getClient(client.id) !== client) {
                throw new Error(`Forge client ${client.id} was disconnected or replaced while waiting for a snapshot`)
            }
            if (client.hello.payload.minecraftVersion !== expectedMinecraftVersion) {
                throw new Error(`Forge client ${client.id} uses Minecraft ${client.hello.payload.minecraftVersion}; expected ${expectedMinecraftVersion}`)
            }
            if (client.lastSnapshotAt === null || client.lastSnapshotAt < client.connectedAt) {
                lastState = `client ${client.id} connected but has not sent a snapshot`
            } else {
                const snapshotAgeMs = Date.now() - client.lastSnapshotAt
                if (snapshotAgeMs <= snapshotFreshnessMs) {
                    return client as BridgeClient
                }
                lastState = `client ${client.id} snapshot is stale (${snapshotAgeMs}ms old)`
            }
        } else if (options.clientId) {
            lastState = `client ${options.clientId} has not connected`
        }

        await delay(Math.min(50, Math.max(1, deadline - Date.now())), options.signal)
    }

    throw new Error(`Timed out after ${timeoutMs}ms waiting for Forge runtime readiness: ${lastState}`)
}

export async function runForgeRuntimeSmoke(options: ForgeRuntimeSmokeOptions) {
    const prerequisites = await inspectForgeRuntimePrerequisites(options)
    const runtime = createClientBridgeRuntime()
    const command = options.command ?? 'none'
    if (command === 'stop_all' && (!Number.isFinite(options.ackTimeoutMs ?? 5_000) || (options.ackTimeoutMs ?? 5_000) <= 0)) {
        throw new Error(`ackTimeoutMs must be a positive number; received ${options.ackTimeoutMs}`)
    }

    try {
        const address = await runtime.start({
            host: options.host ?? '127.0.0.1',
            port: options.port ?? 18765
        })
        options.onListening?.({ address: address.address, port: address.port })

        const client = await waitForForgeRuntimeClient(runtime.getServer(), {
            clientId: options.clientId,
            expectedMinecraftVersion: options.expectedMinecraftVersion,
            timeoutMs: options.connectTimeoutMs,
            snapshotFreshnessMs: options.snapshotFreshnessMs,
            signal: options.signal
        })

        let ack = null
        if (command === 'stop_all') {
            const commandId = `smoke-stop-${Date.now()}`
            ack = await runtime.getServer().sendCommandAndWaitForAck(
                client.id,
                createStopAllCommand(commandId),
                { timeoutMs: options.ackTimeoutMs ?? 5_000 }
            )
            if (ack.payload.status !== 'ok') {
                throw new Error(`Forge client rejected ${commandId}: ${ack.payload.detail || 'no detail'}`)
            }
        }

        return {
            prerequisites,
            client: {
                clientId: client.id,
                connectedAt: client.connectedAt,
                hello: client.hello?.payload || null,
                snapshot: client.snapshot?.payload || null,
                lastSnapshotAt: client.lastSnapshotAt
            },
            ack: ack?.payload || null
        }
    } finally {
        await runtime.stop()
    }
}
