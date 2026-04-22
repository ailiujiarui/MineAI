import net from 'node:net'

function readJsonLine(socket: net.Socket) {
    return new Promise<any>((resolve, reject) => {
        let buffer = ''

        const onData = (chunk: Buffer | string) => {
            buffer += chunk.toString()
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

function writeJsonLine(socket: net.Socket, payload: unknown) {
    return new Promise<void>((resolve, reject) => {
        socket.write(`${JSON.stringify(payload)}\n`, (error) => {
            if (error) reject(error)
            else resolve()
        })
    })
}

export async function withBridgeControlConnection<T>(
    options: { host?: string; port?: number },
    fn: (socket: net.Socket) => Promise<T>
) {
    const socket = await new Promise<net.Socket>((resolve, reject) => {
        const connected = net.createConnection({
            host: options.host || '127.0.0.1',
            port: options.port || 18765
        }, () => resolve(connected))
        connected.once('error', reject)
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

export async function sendBridgeCommand(
    options: { host?: string; port?: number } = {},
    clientId: string,
    command: unknown
) {
    return withBridgeControlConnection(options, async (socket) => {
        await writeJsonLine(socket, {
            type: 'admin_command',
            payload: {
                clientId,
                command
            }
        })
    })
}
