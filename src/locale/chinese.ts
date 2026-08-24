// Chinese input normalization shared by chat and voice command paths.
const ALIASES: Record<string, string> = {
    '木头': 'oak_log',
    '橡木': 'oak_log',
    '橡木原木': 'oak_log',
    '石头': 'stone',
    '圆石': 'cobblestone',
    '煤': 'coal',
    '煤炭': 'coal',
    '铁矿': 'iron_ore',
    '铁矿石': 'iron_ore',
    '铁锭': 'iron_ingot',
    '木板': 'oak_planks',
    '橡木木板': 'oak_planks',
    '木棍': 'stick',
    '火把': 'torch',
    '工作台': 'crafting_table',
    '熔炉': 'furnace',
    '盾牌': 'shield',
    '木镐': 'wooden_pickaxe',
    '石镐': 'stone_pickaxe',
    '铁镐': 'iron_pickaxe'
};

const SPEECH_EDGE_PUNCTUATION = /^[\s,.、;:!?"'()[\]{}，。！？；：“”‘’【】（）]+|[\s,.、;:!?"'()[\]{}，。！？；：“”‘’【】（）]+$/g;

export function normalizeChineseText(text: unknown) {
    return String(text ?? '')
        .normalize('NFKC')
        .trim()
        .replace(/\s+/g, ' ')
        .replace(/[，。！？；：]/g, (char) => ({
        '，': ',', '。': '.', '！': '!', '？': '?', '；': ';', '：': ':'
        }[char] || char));
}

export function resolveMinecraftName(name: unknown) {
    const normalized = normalizeChineseText(name).replace(SPEECH_EDGE_PUNCTUATION, '').toLowerCase();
    return ALIASES[normalized] || normalized.replace(/\s+/g, '_');
}

export function isChineseText(text: unknown) {
    return /[\u3400-\u9fff]/.test(String(text ?? ''));
}

export function isChineseLanguage(language: unknown) {
    return /^zh(?:-|_|$)/i.test(String(language ?? '').trim());
}

export function buildSpawnGreeting(profile: Record<string, unknown> = {}, language?: unknown) {
    const displayName = String(profile.display_name || profile.name || 'NPC');
    const nativeLanguage = profile.native_language || language;
    return isChineseLanguage(nativeLanguage)
        ? `你好，我是${displayName}。`
        : `Hello world! I am ${displayName}`;
}

const RUNTIME_MESSAGES = {
    stuck: ['我卡住了，正在尝试脱困。', "I'm stuck!"],
    free: ['我已经脱困了。', "I'm free."],
    reconnecting: ['连接异常，正在恢复。', 'Restarting.'],
    exiting: ['正在退出。', 'Exiting.'],
    modelUnavailable: ['我的思考服务暂时不可用，请稍后再试。', 'My brain disconnected, try again.']
} as const;

export type RuntimeMessageKey = keyof typeof RUNTIME_MESSAGES;

export function buildRuntimeMessage(
    key: RuntimeMessageKey,
    profile: Record<string, unknown> = {},
    language?: unknown
) {
    const nativeLanguage = profile.native_language || language;
    const messages = RUNTIME_MESSAGES[key];
    return messages[isChineseLanguage(nativeLanguage) ? 0 : 1];
}

export function buildGoalPauseMessage(
    commandName: unknown,
    repeated: boolean,
    profile: Record<string, unknown> = {},
    language?: unknown
) {
    if (isChineseLanguage(profile.native_language || language)) {
        const reason = repeated ? '同一操作连续失败' : '本轮任务失败次数过多';
        return `${reason}，已暂停目标。最后失败的操作：${commandName || '未知'}。请给我新的指令后再继续。`;
    }
    const reason = repeated ? 'The same action failed repeatedly' : 'This goal exceeded its failure budget';
    return `${reason}, so I paused the goal. Last failed action: ${commandName || 'unknown'}. Give me a new instruction to continue.`;
}

export function buildNoCommandStopMessage(
    attempts: number,
    profile: Record<string, unknown> = {},
    language?: unknown
) {
    return isChineseLanguage(profile.native_language || language)
        ? `连续 ${attempts} 次没有生成可执行操作，已停止当前目标。`
        : `Agent did not use a command in the last ${attempts} auto-prompts. Stopping auto-prompting.`;
}

export function getChineseAliasMap() {
    return { ...ALIASES };
}

export function buildCreativeInventoryGuidance(chinese: boolean) {
    return chinese
        ? '\u5f53\u524d\u663e\u793a\u7684\u662f\u771f\u5b9e\u80cc\u5305\uff1b\u53ef\u4f7f\u7528 !creativeItem("item", count) \u6309\u9700\u53d6\u5f97\u521b\u9020\u6a21\u5f0f\u7269\u54c1\uff0c\u65e0\u9700\u91c7\u96c6\u6216\u5408\u6210\u3002'
        : 'This is the physical inventory. Use !creativeItem("item", count) to obtain creative items on demand instead of gathering or crafting.';
}

export function buildCreativeCraftableGuidance(chinese: boolean) {
    return chinese
        ? '\u521b\u9020\u6a21\u5f0f\u65e0\u9700\u5408\u6210\uff1b\u8bf7\u4f7f\u7528 !creativeItem("item", count) \u5c06\u7269\u54c1\u52a0\u5165\u771f\u5b9e\u80cc\u5305\u3002'
        : 'Crafting is unnecessary in creative mode. Use !creativeItem("item", count) to add an item to the physical inventory.';
}
