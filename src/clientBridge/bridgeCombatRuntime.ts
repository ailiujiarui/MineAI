import { planBridgeCombatActions } from './combatPlanner.js'

export function createBridgeCombatRuntime(options: {
    bridgeServer: {
        listClients(): any[]
        sendCommand(clientId: string, command: any): Promise<void>
    }
    attackRange?: number
}) {
    return {
        async tick() {
            const clients = options.bridgeServer.listClients() || []
            for (const client of clients) {
                const snapshot = client?.snapshot?.payload
                if (!snapshot) continue

                const command = {
                    type: 'command' as const,
                    protocolVersion: 1,
                    payload: planBridgeCombatActions(snapshot, {
                        attackRange: options.attackRange
                    })
                }

                await options.bridgeServer.sendCommand(client.id, command)
            }
        }
    }
}
