// @ts-nocheck

function normalizeText(text) {
    return (text || '').trim();
}

function trimSpeechPunctuation(text) {
    return normalizeText(text)
        .replace(/^[\s,，。！？!?.、；;：:]+/, '')
        .replace(/[\s,，。！？!?.、；;：:]+$/g, '')
        .trim();
}

function toNormalizedList(values = []) {
    return values
        .map((value) => trimSpeechPunctuation(value).toLowerCase())
        .filter(Boolean);
}

function stripWakePhrase(text, wakePhrases) {
    const normalized = normalizeText(text);
    const lower = normalized.toLowerCase();

    for (const phrase of wakePhrases) {
        if (lower === phrase) {
            return '';
        }

        if (lower.startsWith(phrase)) {
            const remainder = normalized.slice(phrase.length);
            if (remainder.length === 0) {
                return '';
            }

            const firstChar = remainder[0];
            if (/[\s,，。！？!?.、；;：:]/.test(firstChar)) {
                return trimSpeechPunctuation(remainder);
            }
        }
    }

    return normalized;
}

export function createMicWakeGate(config = {}) {
    const wakePhrases = toNormalizedList(config.wakePhrases || config.wake_phrases || []);
    const directCommands = toNormalizedList(config.directCommands || config.direct_commands || []);
    const wakeWindowMs = config.wakeWindowMs || config.wake_window_ms || 10000;
    const state = {
        windowOpenUntil: 0
    };

    return {
        process(text, now = Date.now()) {
            const normalized = normalizeText(text);
            const cleaned = trimSpeechPunctuation(normalized);
            const lower = cleaned.toLowerCase();

            if (!cleaned) {
                return {
                    accepted: false,
                    reason: 'empty',
                    text: cleaned
                };
            }

            if (directCommands.includes(lower)) {
                return {
                    accepted: true,
                    reason: 'direct-command',
                    text: cleaned
                };
            }

            const matchedWakePhrase = wakePhrases.find((phrase) => {
                if (lower === phrase) {
                    return true;
                }

                if (!normalized.toLowerCase().startsWith(phrase)) {
                    return false;
                }

                const remainder = normalized.slice(phrase.length);
                return remainder.length > 0 && /[\s,，。！？!?.、；;：:]/.test(remainder[0]);
            });
            if (matchedWakePhrase) {
                state.windowOpenUntil = now + wakeWindowMs;
                const stripped = trimSpeechPunctuation(stripWakePhrase(normalized, wakePhrases));
                if (stripped && directCommands.includes(stripped.toLowerCase())) {
                    return {
                        accepted: true,
                        reason: 'direct-command',
                        text: stripped
                    };
                }
                return {
                    accepted: true,
                    reason: 'wake-phrase',
                    text: stripped
                };
            }

            if (state.windowOpenUntil > now) {
                state.windowOpenUntil = 0;
                if (directCommands.includes(lower)) {
                    return {
                        accepted: true,
                        reason: 'direct-command',
                        text: cleaned
                    };
                }
                return {
                    accepted: true,
                    reason: 'wake-window',
                    text: cleaned
                };
            }

            return {
                accepted: false,
                reason: 'ambient',
                text: cleaned
            };
        }
    };
}
