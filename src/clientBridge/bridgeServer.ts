import net from 'node:net'

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

function serializeJsonLine(payload: unknown) {
    return `${JSON.stringify(payload)}\n`
}

export function createClientBridgeServer() {
    const clients = new Map<string, BridgeClientState>()
    const socketState = new WeakMap<net.Socket, { buffer: string; clientId: string | null }>()

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

                const message = JSON.parse(line)
                if (message?.type === 'hello' && message?.payload?.clientId) {
                    const clientId = String(message.payload.clientId)
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
                } else if (message?.type === 'snapshot' && state.clientId) {
                    const current = clients.get(state.clientId)
                    if (current) {
                        current.snapshot = message
                        current.lastSnapshotAt = Date.now()
                    }
                } else if (message?.type === 'ack' && state.clientId) {
                    const current = clients.get(state.clientId)
                    if (current) {
                        current.lastAck = message
                        current.lastAckAt = Date.now()
                    }
                } else if (message?.type === 'admin_list_clients') {
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
                } else if (message?.type === 'admin_command' && message?.payload?.clientId && message?.payload?.command) {
                    const targetId = String(message.payload.clientId)
                    const target = clients.get(targetId)
                    if (target) {
                        target.socket.write(serializeJsonLine(message.payload.command))
                    }
                }
            }
        })

        socket.on('close', () => {
            const state = socketState.get(socket)
            if (state?.clientId) {
                clients.delete(state.clientId)
            }
        })
    })

    return {
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
        async sendCommand(clientId: string, command: CommandMessage) {
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
}
