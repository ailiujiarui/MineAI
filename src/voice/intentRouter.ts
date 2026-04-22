// @ts-nocheck
import { createCommandIntent, createCompanionIntent, createConversationIntent, createGoalIntent } from './voiceRuntime.js';
import { createMicWakeGate } from './micWakeGate.js';

const QUESTION_PREFIXES = ['what', 'why', 'how', 'where', 'who', 'when', 'hello', 'hi', 'hey'];
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

function normalizeText(text) {
    return (text || '').trim();
}

function looksLikeQuestion(text) {
    const lower = text.toLowerCase();
    return text.endsWith('?') || QUESTION_PREFIXES.some(prefix => lower.startsWith(prefix + ' '));
}

function looksLikeImperative(text) {
    const lower = text.toLowerCase();
    return IMPERATIVE_PREFIXES.some(prefix => lower.startsWith(prefix + ' ')) || lower === 'stop';
}

function isConfiguredDirectCommand(text, options = {}) {
    const directCommands = options.micConfig?.direct_commands || options.micConfig?.directCommands || [];
    const normalized = (text || '').trim().toLowerCase();
    return directCommands.some((command) => (command || '').trim().toLowerCase() === normalized);
}

export async function routeVoiceTranscript(event, options = {}) {
    let text = normalizeText(event.text);
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

        text = normalizeText(gateResult.text || text);
        event.metadata = {
            ...(event.metadata || {}),
            triggerMode: 'voice',
            wakeReason: gateResult.reason
        };

        if (gateResult.reason === 'direct-command' || isConfiguredDirectCommand(text, options)) {
            console.log('[voice-debug][intent-router] routed mic transcript to goal intent:', text);
            return createGoalIntent(text, { source: 'voice-goal' });
        }
    }

    if (text.length === 0) {
        return null;
    }

    if (text.startsWith('!')) {
        console.log('[voice-debug][intent-router] explicit command intent:', text);
        return createCommandIntent(text, { source: 'voice-explicit-command' });
    }

    if (text.toLowerCase() === 'stop') {
        console.log('[voice-debug][intent-router] stop command intent');
        return createCommandIntent('!stop', { source: 'voice-stop-command' });
    }

    if (looksLikeQuestion(text) || QUESTION_PREFIXES.some(prefix => text.toLowerCase().startsWith(prefix))) {
        if (companionMode !== 'task') {
            console.log('[voice-debug][intent-router] companion intent:', text);
            return createCompanionIntent(text, { source: 'voice-companion' });
        }
        console.log('[voice-debug][intent-router] conversation intent:', text);
        return createConversationIntent(text, { source: 'voice-conversation' });
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
