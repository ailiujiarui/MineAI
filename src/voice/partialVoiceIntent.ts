// Partial ASR is deliberately narrower than the final intent router.
// It can only produce local, high-priority safety intents.
const PARTIAL_PATTERNS: Array<[RegExp, string]> = [
    [/^(?:停止|停下|停火|别打|别攻击)(?:了|吧)?$/, 'ceasefire'],
    [/^(?:撤退|退后|快撤)(?:了|吧)?$/, 'retreat'],
    [/^(?:保护我|守护我|保护玩家)(?:吧)?$/, 'protect']
];

export function classifyPartialVoiceIntent(text) {
    const normalized = String(text || '')
        .trim()
        .replace(/[\s,.!?;:，。！？；：]+$/g, '');
    if (normalized.length < 2 || normalized.length > 12) return null;
    for (const [pattern, payload] of PARTIAL_PATTERNS) {
        if (pattern.test(normalized)) {
            return { kind: 'combat', payload, source: 'voice-partial-safe' };
        }
    }
    return null;
}
