export async function recoverAgentFromDeath(agent) {
    const recover = async () => {
        agent.death_disconnect_guard?.markDeath?.()
        agent.actions.cancelResume()
        await agent.actions.stop()
        agent.clearBotLogs()
        agent.bot.emit('idle')
    }
    const stateMachine = agent.execution_state_machine
    if (stateMachine?.enabled) return stateMachine.recover(recover)
    return recover()
}
