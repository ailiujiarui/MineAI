export function shouldIgnoreDisconnectAfterDeath({
    lastDeathAt = 0,
    now = Date.now(),
    graceMs = 5000
}) {
    return lastDeathAt > 0 && (now - lastDeathAt) <= graceMs
}

export function createDeathDisconnectGuard(options: { graceMs?: number; now?: () => number } = {}) {
    const graceMs = options.graceMs ?? 5000
    const nowImpl = options.now || (() => Date.now())
    let lastDeathAt = 0

    return {
        markDeath() {
            lastDeathAt = nowImpl()
        },
        shouldSuppressDisconnect() {
            return shouldIgnoreDisconnectAfterDeath({
                lastDeathAt,
                now: nowImpl(),
                graceMs
            })
        }
    }
}

export function shouldHandleRuntimeDisconnect(agent) {
    if (agent?._disconnectHandled) {
        return false
    }
    if (agent?.death_disconnect_guard?.shouldSuppressDisconnect?.()) {
        return false
    }
    return true
}
