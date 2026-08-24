// @ts-nocheck

function normalizeText(text) { return (text || '').trim(); }

function trimSpeechPunctuation(text) {
    return normalizeText(text).replace(/^[\s,，。！？!?.、；;：:]+|[\s,，。！？!?.、；;：:]+$/g, '').trim();
}

function toNormalizedList(values = []) {
    return values.map((value) => trimSpeechPunctuation(value).toLowerCase()).filter(Boolean);
}

export function stripRepeatedWakePhrases(text, wakePhrases = []) {
    let remainder = trimSpeechPunctuation(text);
    const phrases = toNormalizedList(wakePhrases);
    let phrase = '';
    let repeatCount = 0;
    for (let i = 0; i < 4 && remainder; i += 1) {
        const lower = remainder.toLowerCase();
        const matched = phrases.find((candidate) => {
            if (lower === candidate) return true;
            if (!lower.startsWith(candidate)) return false;
            const rest = remainder.slice(candidate.length);
            return /[\p{Script=Han}]$/u.test(candidate)
                || /^[\s,，。！？!?.、；;：:]/.test(rest);
        });
        if (!matched) break;
        phrase ||= matched;
        repeatCount += 1;
        remainder = trimSpeechPunctuation(remainder.slice(matched.length));
    }
    return { matched: repeatCount > 0, phrase, repeatCount, remainder };
}

export function createMicWakeGate(config = {}) {
    const wakePhrases = toNormalizedList(config.wakePhrases || config.wake_phrases || []);
    const directCommands = toNormalizedList(config.directCommands || config.direct_commands || []);
    const wakeWindowMs = config.wakeWindowMs || config.wake_window_ms || 10000;
    const state = { windowOpenUntil: 0 };

    return {
        process(text, now = Date.now()) {
            const cleaned = trimSpeechPunctuation(normalizeText(text));
            const lower = cleaned.toLowerCase();
            if (!cleaned) return { accepted: false, reason: 'empty', text: cleaned };
            if (directCommands.includes(lower)) return { accepted: true, reason: 'direct-command', text: cleaned };

            const wakeMatch = stripRepeatedWakePhrases(cleaned, wakePhrases);
            if (wakeMatch.matched) {
                state.windowOpenUntil = now + wakeWindowMs;
                const stripped = wakeMatch.remainder;
                if (stripped && directCommands.includes(stripped.toLowerCase())) {
                    return { accepted: true, reason: 'direct-command', text: stripped };
                }
                return { accepted: true, reason: 'wake-phrase', text: stripped };
            }
            if (state.windowOpenUntil > now) {
                state.windowOpenUntil = 0;
                return { accepted: true, reason: 'wake-window', text: cleaned };
            }
            return { accepted: false, reason: 'ambient', text: cleaned };
        }
    };
}
