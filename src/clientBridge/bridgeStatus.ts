export function formatBridgeClientSummary(client: any) {
    const hello = client?.hello || {}
    const snapshot = client?.snapshot || {}
    const player = snapshot?.player || {}
    const nearbyEntities = Array.isArray(snapshot?.nearbyEntities) ? snapshot.nearbyEntities : []
    const lastAck = client?.lastAck || null

    const parts = [
        client?.clientId || 'unknown-client',
        `${hello.minecraftVersion || 'unknown-version'}/${hello.loader || 'unknown-loader'}`,
        `combat=${player.combatMode || 'unknown'}`,
        `hp=${player.health ?? '?'}`,
        `food=${player.food ?? '?'}`,
        `entities=${nearbyEntities.length}`
    ]

    if (hello?.modCapabilities?.epicFight) {
        parts.push('epicfight')
    }
    if (hello?.modCapabilities?.slashBlade) {
        parts.push('slashblade')
    }
    if (lastAck) {
        parts.push(`ack=${lastAck.status}:${lastAck.commandId}`)
    }

    return parts.join(' | ')
}
