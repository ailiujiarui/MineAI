// @ts-nocheck
import { createCommandIntent, createCompanionIntent, createConversationIntent, createGoalIntent } from './voiceRuntime.js';
import { createMicWakeGate } from './micWakeGate.js';
import { normalizeChineseText } from '../locale/chinese.js';

const ENGLISH_QUESTION_PREFIXES = ['what', 'why', 'how', 'where', 'who', 'when'];
const ENGLISH_GREETINGS = ['hello', 'hi', 'hey'];
const CHINESE_QUESTION_PREFIXES = [
    '请问', '为什么', '怎么', '怎样', '如何', '什么', '哪里', '哪儿', '哪个', '谁',
    '几点', '什么时候', '多少', '能不能', '可不可以', '有没有'
];
const CHINESE_GREETINGS = ['你好', '早上好', '下午好', '晚上好'];
const IMPERATIVE_PREFIXES = [
    'collect',
    'craft',
    'make',
    'mine',
    'smelt',
    'build',
    'search',
    'find',
    'follow',
    'attack',
    'go',
    'come',
    'equip',
    'use',
    'eat',
    'stop',
    'return'
];
const CHINESE_IMPERATIVE_PREFIXES = ['帮我', '请', '采集', '收集', '挖', '制作', '合成', '建造', '寻找', '搜索', '跟着', '攻击', '去', '过来', '装备', '吃', '停止', '停下', '回家', '回来'];

const STOP_COMMANDS = new Set(['stop', '停', '停止', '停下', '暂停', '别动', '别做了', '不要继续']);
const DIRECT_GOALS = new Map([
    ['跟我来', '跟着我'],
    ['跟着我', '跟着我'],
    ['跟随我', '跟着我'],
    ['回家', '回家'],
    ['返回家里', '回家'],
    ['回基地', '回家'],
    ['返回基地', '回家'],
    ['过来', '过来'],
    ['到我这里来', '过来'],
    ['来我这里', '过来']
]);

function normalizeText(text) {
    return normalizeChineseText(text);
}

function looksLikeQuestion(text) {
    const lower = text.toLowerCase();
    const chineseQuestionEnding = /(?:吗|么|呢|嘛)(?:\s*\?)?$/.test(text);
    return text.endsWith('?')
        || ENGLISH_QUESTION_PREFIXES.some(prefix => lower === prefix || lower.startsWith(prefix + ' '))
        || CHINESE_QUESTION_PREFIXES.some(prefix => text === prefix || text.startsWith(prefix))
        || chineseQuestionEnding;
}

function looksLikeGreeting(text) {
    const lower = text.toLowerCase();
    return ENGLISH_GREETINGS.some(greeting => lower === greeting || lower.startsWith(greeting + ' '))
        || CHINESE_GREETINGS.some(greeting => {
            if (text === greeting) return true;
            if (!text.startsWith(greeting)) return false;
            return /^(?:[\s,!]|啊|呀|哈)/.test(text.slice(greeting.length));
        });
}

function looksLikeImperative(text) {
    const lower = text.toLowerCase();
    return IMPERATIVE_PREFIXES.some(prefix => lower.startsWith(prefix + ' ')) || CHINESE_IMPERATIVE_PREFIXES.some(prefix => text.startsWith(prefix)) || lower === 'stop';
}

function isConfiguredDirectCommand(text, options = {}) {
    const directCommands = options.micConfig?.direct_commands || options.micConfig?.directCommands || [];
    const normalized = (text || '').trim().toLowerCase();
    return directCommands.some((command) => (command || '').trim().toLowerCase() === normalized);
}

function withoutTerminalPunctuation(text) {
    return text.replace(/[\s,.!?;:]+$/g, '').trim();
}

export async function routeVoiceTranscript(event, options = {}) {
    let text = normalizeText(event.text);
    let micDirectCommand = false;
    const commandMode = options.commandMode || 'hybrid';
    const companionMode = options.companionMode || 'task-with-companion-tone';

    if ((event.source || '').toLowerCase() === 'mic') {
        if (!options.micGate) {
            options.micGate = createMicWakeGate(options.micConfig || {});
        }

        const gateResult = options.micGate.process(text);
        console.log('[voice-debug][intent-router] mic gate:', JSON.stringify({
            input: event.text,
            normalizedInput: text,
            accepted: gateResult.accepted,
            reason: gateResult.reason,
            output: gateResult.text
        }));
        if (!gateResult.accepted) {
            return null;
        }

        text = normalizeText(gateResult.text ?? text);
        micDirectCommand = gateResult.reason === 'direct-command';
        event.metadata = {
            ...(event.metadata || {}),
            triggerMode: 'voice',
            wakeReason: event.metadata?.transcriptAggregation === 'wake-followup'
                ? 'wake-followup'
                : gateResult.reason
        };

        // Direct commands still pass through the safe classifiers below. In particular,
        // configured stop phrases must become !stop rather than a long-running goal.
    }

    if (text.length === 0) {
        return null;
    }

    if (text.startsWith('!')) {
        console.log('[voice-debug][intent-router] explicit command intent:', text);
        return createCommandIntent(text, { source: 'voice-explicit-command' });
    }

    const commandText = withoutTerminalPunctuation(text);
    const lowerCommandText = commandText.toLowerCase();
    if (STOP_COMMANDS.has(lowerCommandText)) {
        console.log('[voice-debug][intent-router] stop command intent');
        return createCommandIntent('!stop', { source: 'voice-stop-command' });
    }

    if (looksLikeQuestion(text) || looksLikeGreeting(text)) {
        if (companionMode !== 'task') {
            console.log('[voice-debug][intent-router] companion intent:', text);
            return createCompanionIntent(text, { source: 'voice-companion' });
        }
        console.log('[voice-debug][intent-router] conversation intent:', text);
        return createConversationIntent(text, { source: 'voice-conversation' });
    }

    const directGoal = DIRECT_GOALS.get(lowerCommandText);
    if (directGoal && (micDirectCommand || isConfiguredDirectCommand(commandText, options) || commandMode !== 'conversation-only')) {
        console.log('[voice-debug][intent-router] normalized direct goal intent:', directGoal);
        return createGoalIntent(directGoal, { source: 'voice-direct-goal' });
    }

    if (commandMode === 'conversation-only') {
        if (companionMode !== 'task') {
            console.log('[voice-debug][intent-router] companion intent (conversation-only):', text);
            return createCompanionIntent(text, { source: 'voice-companion' });
        }
        console.log('[voice-debug][intent-router] conversation intent (conversation-only):', text);
        return createConversationIntent(text, { source: 'voice-conversation' });
    }

    if (commandMode === 'command-only' || looksLikeImperative(text)) {
        console.log('[voice-debug][intent-router] goal intent:', text);
        return createGoalIntent(text, { source: 'voice-goal' });
    }

    console.log('[voice-debug][intent-router] fallback conversation intent:', text);
    return createConversationIntent(text, { source: 'voice-conversation' });
}
