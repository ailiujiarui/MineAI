// @ts-nocheck

export function createVoiceReplyTracker() {
    let pendingVoiceReply = false;

    return {
        arm(event = {}) {
            if (event?.metadata?.triggerMode === 'voice' || event?.source === 'mic') {
                pendingVoiceReply = true;
            }
        },
        shouldSpeak(mode = 'always') {
            if (mode !== 'voice-triggered-only') {
                return true;
            }

            const allowed = pendingVoiceReply;
            pendingVoiceReply = false;
            return allowed;
        },
        reset() {
            pendingVoiceReply = false;
        }
    };
}

export function isSkippableVoiceReplyText(spokenText = '') {
    const normalized = (spokenText || '').trim();
    if (normalized.length === 0) {
        return true;
    }

    // Skip synthetic command acknowledgement lines such as "*user used goal*"
    if (/^\*.+\*$/u.test(normalized) && /\bused\b/i.test(normalized)) {
        return true;
    }

    return false;
}

export function shouldSpeakVoiceReply(tracker, mode = 'always', spokenText = '') {
    if (isSkippableVoiceReplyText(spokenText)) {
        return false;
    }

    return tracker?.shouldSpeak?.(mode) ?? true;
}
