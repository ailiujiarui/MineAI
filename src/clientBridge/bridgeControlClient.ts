import net from 'node:net'

function readJsonLine(socket: net.Socket, timeoutMs = 5000) {
    return new Promise<any>((resolve, reject) => {
        let buffer = ''

        const cleanup = () => {
            clearTimeout(timer)
            socket.off('data', onData)
            socket.off('error', onError)
            socket.off('close', onClose)
        }

        const finish = (fn: (value?: any) => void, value?: any) => {
            cleanup()
            fn(value)
        }

        const onData = (chunk: Buffer | string) => {
            buffer += chunk.toString()
            const newline = buffer.indexOf('\n')
            if (newline >= 0) {
                try {
                    finish(resolve, JSON.parse(buffer.slice(0, newline)))
                } catch (error) {
                    finish(reject, error)
                }
            }
        }

        const onError = (error: Error) => finish(reject, error)
        const onClose = () => finish(reject, new Error('Bridge connection closed before a response was received'))

        const timer = setTimeout(() => {
            finish(reject, new Error(`Timed out after ${timeoutMs}ms waiting for bridge response`))
        }, timeoutMs)

        socket.on('data', onData)
        socket.once('error', onError)
        socket.once('close', onClose)
    })
}

function writeJsonLine(socket: net.Socket, payload: unknown) {
    return new Promise<void>((resolve, reject) => {
        socket.write(`${JSON.stringify(payload)}\n`, (error) => {
            if (error) reject(error)
            else resolve()
        })
    })
}

export async function withBridgeControlConnection<T>(
    options: { host?: string; port?: number; connectTimeoutMs?: number },
    fn: (socket: net.Socket) => Promise<T>
) {
    const socket = await new Promise<net.Socket>((resolve, reject) => {
        const timeoutMs = options.connectTimeoutMs ?? 1000
        let timer: NodeJS.Timeout
        const onError = (error: Error) => {
            clearTimeout(timer)
            reject(error)
        }
        const connected = net.createConnection({
            host: options.host || '127.0.0.1',
            port: options.port || 18765
        }, () => {
            clearTimeout(timer)
            connected.off('error', onError)
            resolve(connected)
        })
        timer = setTimeout(() => {
            connected.destroy()
            reject(new Error(`Timed out after ${timeoutMs}ms connecting to bridge`))
        }, timeoutMs)
        connected.once('error', onError)
    })

    try {
        return await fn(socket)
    } finally {
        socket.destroy()
    }
}

export async function listBridgeClients(options: { host?: string; port?: number } = {}) {
    return withBridgeControlConnection(options, async (socket) => {
        const responsePromise = readJsonLine(socket)
        await writeJsonLine(socket, { type: 'admin_list_clients' })
        const response = await responsePromise
        return response?.payload?.clients || []
    })
}

export async function requestBridgeStopAll(options: {
    host?: string
    port?: number
    requestId: string
    clientId?: string
    targetPlayerName?: string
    snapshotFreshnessMs?: number
    ackTimeoutMs?: number
}) {
    const host = options.host || '127.0.0.1'
    if (!['127.0.0.1', 'localhost', '::1'].includes(host.toLowerCase())) {
        throw new Error(`Forge stop bridge host must be loopback; received ${host}`)
    }
    const requestedAckTimeoutMs = options.ackTimeoutMs ?? 3_000
    if (!Number.isFinite(requestedAckTimeoutMs) || requestedAckTimeoutMs <= 0) {
        throw new Error(`ackTimeoutMs must be a positive number; received ${requestedAckTimeoutMs}`)
    }
    const ackTimeoutMs = Math.min(requestedAckTimeoutMs, 3_000)
    const responseTimeoutMs = ackTimeoutMs + 1_000
    return withBridgeControlConnection({ ...options, host }, async (socket) => {
        const responsePromise = readJsonLine(socket, responseTimeoutMs)
        await writeJsonLine(socket, {
            type: 'admin_stop_all',
            payload: {
                requestId: options.requestId,
                clientId: options.clientId,
                targetPlayerName: options.targetPlayerName,
                snapshotFreshnessMs: options.snapshotFreshnessMs,
                ackTimeoutMs
            }
        })
        const response = await responsePromise
        if (response?.type !== 'admin_stop_all_result' || response?.payload?.requestId !== options.requestId) {
            throw new Error('Bridge returned an invalid stop response')
        }
        return response.payload
    })
}

export async function sendBridgeCommand(
    options: { host?: string; port?: number } = {},
    clientId: string,
    command: unknown
) {
    void options
    void clientId
    void command
    throw new Error('Legacy arbitrary bridge commands are disabled; use requestBridgeStopAll')
}
