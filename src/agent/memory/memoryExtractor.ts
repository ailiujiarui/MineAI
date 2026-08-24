export interface ExtractedMemory {
    kind: 'fact' | 'preference';
    key?: string;
    value: string;
}

const RULES: Array<{ pattern: RegExp; build(match: RegExpMatchArray): ExtractedMemory }> = [
    { pattern: /^(?:我叫|我的名字是)\s*([^，。,.!?！？]{1,24})/, build: (m) => ({ kind: 'fact', key: 'player_name', value: m[1].trim() }) },
    { pattern: /^(?:我喜欢|我最喜欢)\s*([^，。,.!?！？]{1,48})/, build: (m) => ({ kind: 'preference', value: m[1].trim() }) },
    { pattern: /^(?:我不喜欢|我讨厌)\s*([^，。,.!?！？]{1,48})/, build: (m) => ({ kind: 'preference', value: `不喜欢${m[1].trim()}` }) },
    { pattern: /^(?:记住|请记住)[：:\s]*(.{2,80})/, build: (m) => ({ kind: 'fact', key: `note:${m[1].trim().slice(0, 24)}`, value: m[1].trim() }) }
];

export function extractChineseMemories(text: unknown): ExtractedMemory[] {
    const input = String(text ?? '').trim();
    if (!input) return [];
    for (const rule of RULES) {
        const match = input.match(rule.pattern);
        if (match) return [rule.build(match)];
    }
    return [];
}
