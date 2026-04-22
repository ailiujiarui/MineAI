// @ts-nocheck

export function buildTaskNarration(state, update) {
    if (state.mode === 'task') {
        return `Task update: ${update.stage}. Next: ${update.nextCommand}.`;
    }

    if (state.mode === 'companion') {
        return `I'm here with you. Right now I'm handling ${update.stage}, and next I'm going to use ${update.nextCommand}.`;
    }

    return `I'm on ${update.stage} now, and I'll handle it step by step. Next up: ${update.nextCommand}.`;
}

export function buildCompanionReply(state, event) {
    const text = (event.text || '').trim();

    if (state.mode === 'task') {
        return `Status noted: ${text}`;
    }

    if (text.endsWith('?')) {
        return `I'm here with you. ${text} I'm staying focused and keeping us moving.`;
    }

    return `I'm with you. ${text}`;
}

export function buildTaskFailureNarration(state, update) {
    if (state.mode === 'task') {
        return `Task issue: ${update.stage}. Failed at ${update.failedCommand}. Reason: ${update.reason}`;
    }

    return `I'm still with you. ${update.stage} hit a snag on ${update.failedCommand}, but I'll adjust and try a safer next step. Reason: ${update.reason}`;
}
