// @ts-nocheck

export function createVoiceTranscriptEvent(overrides = {}) {
    return {
        text: '',
        source: 'asr',
        speakerId: 'unknown',
        timestamp: Date.now(),
        metadata: {},
        ...overrides
    };
}

export function createTtsRequest(overrides = {}) {
    return {
        text: '',
        channel: 'status',
        metadata: {},
        ...overrides
    };
}
