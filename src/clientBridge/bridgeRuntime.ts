import { createClientBridgeServer } from './bridgeServer.js'

export function createClientBridgeRuntime() {
    const server = createClientBridgeServer()
    let running = false

    return {
        async start(options: { host?: string; port?: number } = {}) {
            if (running) {
                return server.address()
            }

            await server.listen(options.port ?? 18765, options.host ?? '127.0.0.1')
            running = true
            return server.address()
        },
        address() {
            return server.address()
        },
        isRunning() {
            return running
        },
        getServer() {
            return server
        },
        async stop() {
            if (!running) return
            await server.close()
            running = false
        }
    }
}
