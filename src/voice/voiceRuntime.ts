// @ts-nocheck
import { createTtsRequest, createVoiceTranscriptEvent } from './voiceTypes.js';
import { routeVoiceTranscript } from './intentRouter.js';
import { playAudioBuffer } from './localAudioPlayer.js';
import { createMicTranscriptAggregator } from './micTranscriptAggregator.js';
import { serverProxy } from '../agent/mindserver_proxy.js';

export function createCommandIntent(payload, meta = {}) {
    return {
        kind: 'command',
        payload,
        meta
    };
}

export function createConversationIntent(payload, meta = {}) {
    return {
        kind: 'conversation',
        payload,
        meta
    };
}

export function createGoalIntent(payload, meta = {}) {
    return {
        kind: 'goal',
        payload,
        meta
    };
}

export function createCompanionIntent(payload, meta = {}) {
    return {
        kind: 'companion',
        payload,
        meta
    };
}

export class NullTtsAdapter {
    async synthesize(request) {
        return {
            provider: 'null',
            audio: Buffer.alloc(0),
            mimeType: 'application/octet-stream',
            request
        };
    }
}

export class VoiceRuntime {
    constructor(config = {}) {
        this.enabled = config.enabled ?? false;
        this.ttsAdapter = config.ttsAdapter || new NullTtsAdapter();
        this.router = config.router || (async (event) => routeVoiceTranscript(event, config));
        this.onIntent = config.onIntent || (async () => {});
        this.playAudio = config.playAudio || playAudioBuffer;
        this.localPlayback = config.localPlayback ?? true;
        this.setPlaybackState = config.setPlaybackState
            || ((active) => serverProxy?.setVoicePlaybackState?.(active));
        this.micTranscriptAggregator = config.micTranscriptAggregator
            || createMicTranscriptAggregator(config.micConfig || {});
    }

    setEnabled(enabled) {
        this.enabled = enabled;
        if (!enabled) this.micTranscriptAggregator?.clear?.();
    }

    setVoiceProfile(profileName) {
        if (typeof this.ttsAdapter?.setActiveProfile === 'function') {
            return this.ttsAdapter.setActiveProfile(profileName);
        }
        return false;
    }

    getVoiceProfile() {
        if (typeof this.ttsAdapter?.getActiveProfile === 'function') {
            return this.ttsAdapter.getActiveProfile();
        }
        return null;
    }

    async handleTranscript(event) {
        if (!this.enabled) {
            return null;
        }

        const rawTranscript = createVoiceTranscriptEvent(event);
        const transcript = await this.micTranscriptAggregator.process(rawTranscript);
        if (!transcript) {
            return null;
        }
        const intent = await this.router(transcript);
        if (!intent) {
            return null;
        }

        await this.onIntent(intent, transcript);
        return intent;
    }

    async speak(request) {
        if (!this.enabled) {
            return null;
        }

        const normalized = createTtsRequest(request);
        const result = await this.ttsAdapter.synthesize(normalized);
        if (this.localPlayback && result?.audio?.length) {
            try {
                this.setPlaybackState(true);
                await this.playAudio(result.audio, result.mimeType);
            } finally {
                try { this.setPlaybackState(false); } catch (error) {
                    console.warn('[voice] failed to clear playback suppression:', error);
                }
            }
        }
        return result;
    }
}
