// @ts-nocheck

function buildPrompt(lines) {
    return lines.join(' ');
}

export function decideRecoveryAction(snapshot) {
    const hunger = snapshot.hunger ?? 20;
    const health = snapshot.health ?? 20;
    const hostileCount = snapshot.hostileCount ?? 0;
    const dangerScore = snapshot.dangerScore ?? 0;

    if (health <= 8 && (hostileCount > 0 || dangerScore >= 7)) {
        return {
            stage: 'emergency_retreat',
            priority: 'critical',
            goalPrompt: buildPrompt([
                'Emergency retreat: you are in immediate danger and must survive first.',
                'Check !stats and !inventory immediately.',
                'Use !goToSurface to escape underground risk, break line of sight, and reset the fight.',
                'Do not continue mining or crafting until health and surroundings are stable again.'
            ])
        };
    }

    if (hunger <= 8) {
        return {
            stage: 'recover_food',
            priority: 'high',
            goalPrompt: buildPrompt([
                'Food recovery takes priority right now.',
                'Inspect !stats, !inventory, and !nearbyBlocks before acting.',
                'If edible food exists, consume it first; otherwise gather or hunt a safe food source before resuming progression.',
                'Avoid deep mining until hunger is back to a safe level.'
            ])
        };
    }

    if (dangerScore >= 8 || hostileCount >= 3) {
        return {
            stage: 'stabilize_safety',
            priority: 'high',
            goalPrompt: buildPrompt([
                'Stabilize safety before continuing the main progression.',
                'Use !stats and !nearbyBlocks to reassess threats.',
                'If pathing or cave pressure is risky, use !goToSurface and re-enter only when safe.',
                'Do not commit to long actions until the hostile pressure drops.'
            ])
        };
    }

    return null;
}
