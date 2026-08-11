// @ts-nocheck

export function createCompanionState(overrides = {}) {
    return {
        mode: 'task-with-companion-tone',
        language: 'en',
        lastTaskStage: null,
        lastTaskCommand: null,
        lastSpeakerId: null,
        ...overrides
    };
}
