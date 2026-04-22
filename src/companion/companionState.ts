// @ts-nocheck

export function createCompanionState(overrides = {}) {
    return {
        mode: 'task-with-companion-tone',
        lastTaskStage: null,
        lastTaskCommand: null,
        lastSpeakerId: null,
        ...overrides
    };
}
