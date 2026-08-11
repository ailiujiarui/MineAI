// @ts-nocheck
import { stripRepeatedWakePhrases } from './micWakeGate.js';

function cleanSpeechText(text) {
    return String(text || '').trim().replace(/^[\s,，。！？!?.、；;：:]+|[\s,，。！？!?.、；;：:]+$/g, '').trim();
}
function normalizedList(values = []) { return values.map((value) => cleanSpeechText(value).toLowerCase()).filter(Boolean); }
function clampNumber(value, fallback, minimum, maximum) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.min(maximum, Math.max(minimum, parsed)) : fallback;
}

export function createMicTranscriptAggregator(config = {}) {
    const wakePhrases = normalizedList(config.wakePhrases || config.wake_phrases || []);
    const directCommands = new Set(normalizedList(config.directCommands || config.direct_commands || []));
    const silenceMs = clampNumber(config.wakeFollowupSilenceMs || config.wake_followup_silence_ms, 800, 100, 3000);
    const maxWaitMs = clampNumber(config.wakeFollowupMaxWaitMs || config.wake_followup_max_wait_ms, 10000, 3000, 15000);
    const schedule = config.schedule || setTimeout;
    const cancelSchedule = config.cancelSchedule || clearTimeout;
    const pendingBySpeaker = new Map();
    const speakerKey = (event) => String(event.speakerId || 'voice_user');
    const clearTimers = (pending) => { if (pending.silenceTimer) cancelSchedule(pending.silenceTimer); if (pending.hardTimer) cancelSchedule(pending.hardTimer); };
    function finish(key, pending, shouldRoute) {
        if (pendingBySpeaker.get(key) !== pending) return;
        pendingBySpeaker.delete(key); clearTimers(pending);
        if (!shouldRoute || pending.fragments.length === 0) return pending.resolve(null);
        pending.resolve({ ...pending.event, text: [pending.wakePhrase, ...pending.fragments].join(' '), metadata: { ...(pending.event.metadata || {}), transcriptAggregation: 'wake-followup', transcriptFragmentCount: pending.fragments.length } });
    }
    function cancel(key) { const pending = pendingBySpeaker.get(key); if (pending) finish(key, pending, false); }
    return {
        async process(event) {
            if ((event.source || '').toLowerCase() !== 'mic' || wakePhrases.length === 0) return event;
            const key = speakerKey(event); const cleaned = cleanSpeechText(event.text); const lower = cleaned.toLowerCase();
            if (!cleaned) return null;
            if (directCommands.has(lower)) { cancel(key); return event; }
            const normalizedWake = stripRepeatedWakePhrases(cleaned, wakePhrases);
            const wakeMatch = normalizedWake.matched ? { phrase: normalizedWake.phrase, remainder: normalizedWake.remainder } : null;
            if (wakeMatch?.remainder) { cancel(key); return { ...event, text: [wakeMatch.phrase, wakeMatch.remainder].join(' ') }; }
            if (wakeMatch) {
                cancel(key);
                return new Promise((resolve) => {
                    const pending = { event, wakePhrase: wakeMatch.phrase, fragments: [], silenceTimer: null, hardTimer: null, resolve };
                    pending.hardTimer = schedule(() => finish(key, pending, pending.fragments.length > 0), maxWaitMs);
                    pendingBySpeaker.set(key, pending);
                });
            }
            const pending = pendingBySpeaker.get(key); if (!pending) return event;
            pending.fragments.push(cleaned);
            if (pending.silenceTimer) cancelSchedule(pending.silenceTimer);
            pending.silenceTimer = schedule(() => finish(key, pending, true), silenceMs);
            return null;
        },
        clear() { for (const [key, pending] of pendingBySpeaker) finish(key, pending, false); }
    };
}
