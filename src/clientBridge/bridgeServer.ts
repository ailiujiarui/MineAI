import net from 'node:net'
import { randomUUID } from 'node:crypto'
import {
    isBridgeAckMessage,
    isBridgeHelloMessage,
    isBridgeSnapshotMessage
} from './clientProtocol.js'

type HelloMessage = ReturnType<typeof import('./clientProtocol.js').createHelloMessage>
type SnapshotMessage = ReturnType<typeof import('./clientProtocol.js').createSnapshotMessage>
type CommandMessage = ReturnType<typeof import('./clientProtocol.js').createCommandMessage>
type AckMessage = ReturnType<typeof import('./clientProtocol.js').createAckMessage>

interface BridgeClientState {
    id: string
    socket: net.Socket
    connectedAt: number
    hello: HelloMessage | null
    snapshot: SnapshotMessage | null
    lastSnapshotAt: number | null
    lastAck: AckMessage | null
    lastAckAt: number | null
}

interface PendingAck {
    socket: net.Socket
    timer: NodeJS.Timeout
    resolve: (ack: AckMessage) => void
    reject: (error: Error) => void
}

interface AdminStopRequest {
    type: 'admin_stop_all'
    payload: {
        requestId: string
        clientId?: string
        targetPlayerName?: string
        snapshotFreshnessMs?: number
        ackTimeoutMs?: number
    }
}

function serializeJsonLine(payload: unknown) {
    return `${JSON.stringify(payload)}\n`
}

export function createClientBridgeServer() {
    const clients = new Map<string, BridgeClientState>()
    const socketState = new WeakMap<net.Socket, { buffer: string; clientId: string | null }>()
    const pendingAcks = new Map<string, PendingAck>()
    const adminStopInflight = new Map<BridgeClientState, Promise<Record<string, unknown>>>()
    const lastAdminStopAt = new Map<BridgeClientState, number>()
    const ADMIN_STOP_COOLDOWN_MS = 250

    const ackKey = (clientId: string, commandId: string) => `${clientId}\u0000${commandId}`

    function isAdminStopRequest(message: unknown): message is AdminStopRequest {
        if (!message || typeof message !== 'object') return false
        const value = message as any
        const payload = value.payload
        return value.type === 'admin_stop_all'
            && payload && typeof payload === 'object'
            && typeof payload.requestId === 'string'
            && payload.requestId.trim().length > 0
            && (payload.clientId === undefined || typeof payload.clientId === 'string')
            && (payload.targetPlayerName === undefined || typeof payload.targetPlayerName === 'string')
            && (payload.snapshotFreshnessMs === undefined || (Number.isFinite(payload.snapshotFreshnessMs) && payload.snapshotFreshnessMs > 0))
            && (payload.ackTimeoutMs === undefined || (Number.isFinite(payload.ackTimeoutMs) && payload.ackTimeoutMs > 0))
    }

    function sendAdminStopResult(socket: net.Socket, payload: Record<string, unknown>) {
        if (!socket.destroyed) {
            socket.write(serializeJsonLine({ type: 'admin_stop_all_result', payload }))
        }
    }

    function selectAdminStopClient(request: AdminStopRequest) {
        const freshnessMs = request.payload.snapshotFreshnessMs ?? 2_000
        const now = Date.now()
        let candidates = [...clients.values()].filter((client) => client.hello
            && client.snapshot
            && client.lastSnapshotAt !== null
            && now - client.lastSnapshotAt <= freshnessMs)

        if (request.payload.clientId) {
            candidates = candidates.filter((client) => client.id === request.payload.clientId)
        }
        if (request.payload.targetPlayerName) {
            candidates = candidates.filter((client) => client.snapshot?.payload.player.name === request.payload.targetPlayerName)
        }

        if (candidates.length === 0) {
            return { error: 'No matching fresh Forge client is available', status: 'unavailable' as const }
        }
        if (candidates.length > 1) {
            return { error: 'Multiple matching Forge clients are available; configure an exact target', status: 'rejected' as const }
        }
        return { client: candidates[0] }
    }

    async function handleAdminStopRequest(socket: net.Socket, request: AdminStopRequest) {
        const selected = selectAdminStopClient(request)
        if (!selected.client) {
            sendAdminStopResult(socket, {
                requestId: request.payload.requestId,
                status: selected.status,
                message: selected.error
            })
            return
        }

        const clientId = selected.client.id
        const existing = adminStopInflight.get(selected.client)
        if (existing) {
            const result = await existing
            sendAdminStopResult(socket, { ...result, requestId: request.payload.requestId, coalesced: true })
            return
        }

        const sinceLastStop = Date.now() - (lastAdminStopAt.get(selected.client) || 0)
        if (sinceLastStop < ADMIN_STOP_COOLDOWN_MS) {
            sendAdminStopResult(socket, {
                requestId: request.payload.requestId,
                clientId,
                status: 'rejected',
                message: `Forge stop rate limit active; retry after ${ADMIN_STOP_COOLDOWN_MS - sinceLastStop}ms`
            })
            return
        }

        const commandId = `stop-${randomUUID()}`
        const operation = executeAdminStop(selected.client, commandId, request.payload.ackTimeoutMs ?? 3_000)
        adminStopInflight.set(selected.client, operation)
        lastAdminStopAt.set(selected.client, Date.now())
        try {
            sendAdminStopResult(socket, { ...(await operation), requestId: request.payload.requestId })
        } finally {
            if (adminStopInflight.get(selected.client) === operation) adminStopInflight.delete(selected.client)
        }
    }

    async function executeAdminStop(client: BridgeClientState, commandId: string, timeoutMs: number) {
        const command: CommandMessage = {
            type: 'command',
            protocolVersion: 1,
            payload: { id: commandId, actions: [{ kind: 'stop_all' }] }
        }
        try {
            const ack = await sendCommandAndWaitForAckForClient(client, command, timeoutMs)
            return {
                clientId: client.id,
                commandId,
                status: ack.payload.status === 'ok' ? 'ok' : 'rejected',
                ack: ack.payload
            }
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            return {
                clientId: client.id,
                commandId,
                status: /Timed out/i.test(message)
                    ? 'timeout'
                    : /disconnect|replaced/i.test(message)
                        ? 'disconnected'
                        : 'rejected',
                message
            }
        }
    }

    function rejectPendingForSocket(socket: net.Socket, reason: Error) {
        for (const [key, pending] of pendingAcks) {
            if (pending.socket !== socket) continue
            clearTimeout(pending.timer)
            pendingAcks.delete(key)
            pending.reject(reason)
        }
    }

    async function sendCommand(clientId: string, command: CommandMessage) {
        const client = clients.get(clientId)
        if (!client) {
            throw new Error(`Client ${clientId} is not connected`)
        }

        await new Promise<void>((resolve, reject) => {
            client.socket.write(serializeJsonLine(command), (error) => {
                if (error) reject(error)
                else resolve()
            })
        })
    }

    async function sendCommandAndWaitForAckForClient(client: BridgeClientState, command: CommandMessage, timeoutMs: number) {
        if (clients.get(client.id) !== client || client.socket.destroyed) {
            throw new Error(`Client ${client.id} was replaced or disconnected before command send`)
        }
        const commandId = command?.payload?.id
        if (!commandId) throw new Error('Bridge command must have a non-empty payload.id')
        const key = ackKey(client.id, commandId)
        if (pendingAcks.has(key)) {
            throw new Error(`Command ${commandId} is already awaiting acknowledgement from ${client.id}`)
        }

        const ackPromise = new Promise<AckMessage>((resolve, reject) => {
            const timer = setTimeout(() => {
                pendingAcks.delete(key)
                reject(new Error(`Timed out after ${timeoutMs}ms waiting for command ${commandId} acknowledgement from ${client.id}`))
            }, timeoutMs)
            pendingAcks.set(key, { socket: client.socket, timer, resolve, reject })
        })

        try {
            if (clients.get(client.id) !== client || client.socket.destroyed) {
                throw new Error(`Client ${client.id} was replaced or disconnected before command send`)
            }
            const writePromise = new Promise<void>((resolve, reject) => {
                client.socket.write(serializeJsonLine(command), (error) => error ? reject(error) : resolve())
            })
            const [, ack] = await Promise.all([writePromise, ackPromise])
            return ack
        } catch (error) {
            const pending = pendingAcks.get(key)
            if (pending) {
                clearTimeout(pending.timer)
                pendingAcks.delete(key)
            }
            throw error
        }
    }

    const server = net.createServer((socket) => {
        socket.setEncoding('utf8')
        socketState.set(socket, { buffer: '', clientId: null })

        socket.on('data', (chunk: string) => {
            const state = socketState.get(socket)
            if (!state) return

            state.buffer += chunk
            while (true) {
                const newline = state.buffer.indexOf('\n')
                if (newline < 0) break

                const line = state.buffer.slice(0, newline).trim()
                state.buffer = state.buffer.slice(newline + 1)
                if (!line) continue

                let message: unknown
                try {
                    message = JSON.parse(line)
                } catch {
                    continue
                }

                if (isBridgeHelloMessage(message)) {
                    const clientId = message.payload.clientId
                    if (state.clientId && state.clientId !== clientId) {
                        socket.destroy(new Error(`Bridge connection cannot change clientId from ${state.clientId} to ${clientId}`))
                        return
                    }
                    const previous = clients.get(clientId)
                    if (previous && previous.socket !== socket) {
                        rejectPendingForSocket(previous.socket, new Error(`Client ${clientId} was replaced by a new connection`))
                        previous.socket.destroy()
                    }
                    state.clientId = clientId
                    clients.set(clientId, {
                        id: clientId,
                        socket,
                        connectedAt: Date.now(),
                        hello: message,
                        snapshot: null,
                        lastSnapshotAt: null,
                        lastAck: null,
                        lastAckAt: null
                    })
                } else if (isBridgeSnapshotMessage(message) && state.clientId) {
                    const current = clients.get(state.clientId)
                    if (current?.socket === socket) {
                        current.snapshot = message
                        current.lastSnapshotAt = Date.now()
                    }
                } else if (isBridgeAckMessage(message) && state.clientId) {
                    const current = clients.get(state.clientId)
                    if (current?.socket === socket) {
                        current.lastAck = message
                        current.lastAckAt = Date.now()
                        const key = ackKey(state.clientId, message.payload.commandId)
                        const pending = pendingAcks.get(key)
                        if (pending?.socket === socket) {
                            clearTimeout(pending.timer)
                            pendingAcks.delete(key)
                            pending.resolve(message)
                        }
                    }
                } else if ((message as any)?.type === 'admin_list_clients') {
                    socket.write(serializeJsonLine({
                        type: 'admin_clients',
                        payload: {
                            clients: [...clients.values()].map((client) => ({
                                clientId: client.id,
                                connectedAt: client.connectedAt,
                                hello: client.hello?.payload || null,
                                snapshot: client.snapshot?.payload || null,
                                lastSnapshotAt: client.lastSnapshotAt,
                                lastAck: client.lastAck?.payload || null,
                                lastAckAt: client.lastAckAt
                            }))
                        }
                    }))
                } else if (isAdminStopRequest(message)) {
                    void handleAdminStopRequest(socket, message)
                } else if ((message as any)?.type === 'admin_stop_all') {
                    sendAdminStopResult(socket, {
                        requestId: (message as any)?.payload?.requestId || null,
                        status: 'rejected',
                        message: 'Invalid admin_stop_all request'
                    })
                } else if ((message as any)?.type === 'admin_command') {
                    socket.write(serializeJsonLine({
                        type: 'admin_command_result',
                        payload: {
                            status: 'rejected',
                            message: 'Legacy arbitrary admin commands are disabled; use admin_stop_all'
                        }
                    }))
                }
            }
        })

        socket.on('close', () => {
            const state = socketState.get(socket)
            if (state?.clientId) {
                const current = clients.get(state.clientId)
                if (current?.socket === socket) {
                    clients.delete(state.clientId)
                }
            }
            rejectPendingForSocket(socket, new Error(`Client ${state?.clientId || 'unknown'} disconnected before acknowledging command`))
        })
        socket.on('error', () => {
            // The close handler owns connection and pending-command cleanup.
        })
    })

    const api = {
        async listen(port = 0, host = '127.0.0.1') {
            await new Promise<void>((resolve, reject) => {
                server.listen(port, host, () => resolve())
                server.once('error', reject)
            })
        },
        address() {
            const address = server.address()
            if (!address || typeof address === 'string') {
                throw new Error('Bridge server is not listening on a TCP address')
            }
            return address
        },
        listClients() {
            return [...clients.values()]
        },
        getClient(clientId: string) {
            return clients.get(clientId) || null
        },
        sendCommand,
        async sendCommandAndWaitForAck(
            clientId: string,
            command: CommandMessage,
            options: { timeoutMs?: number } = {}
        ) {
            const client = clients.get(clientId)
            if (!client) {
                throw new Error(`Client ${clientId} is not connected`)
            }

            const timeoutMs = options.timeoutMs ?? 5000
            return sendCommandAndWaitForAckForClient(client, command, timeoutMs)
        },
        async close() {
            for (const client of clients.values()) {
                client.socket.destroy()
            }
            clients.clear()
            await new Promise<void>((resolve, reject) => {
                server.close((error) => {
                    if (error) reject(error)
                    else resolve()
                })
            })
        }
    }
    return api
}
