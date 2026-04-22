export async function recoverAgentFromDeath(agent) {
    agent.death_disconnect_guard?.markDeath?.()
    agent.actions.cancelResume()
    await agent.actions.stop()
    agent.clearBotLogs()
    agent.bot.emit('idle')
}
