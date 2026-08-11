// @ts-nocheck
import { isChineseLanguage } from '../locale/chinese.js';

export function buildTaskNarration(state, update) {
    if (isChineseLanguage(state.language)) {
        if (state.mode === 'task') return `当前任务：${update.stage}。下一步：${update.nextCommand}。`;
        if (state.mode === 'companion') return `我在陪着你。现在正在处理${update.stage}，接下来会执行${update.nextCommand}。`;
        return `我正在处理${update.stage}，会一步一步完成。下一步是${update.nextCommand}。`;
    }
    if (state.mode === 'task') {
        return `Task update: ${update.stage}. Next: ${update.nextCommand}.`;
    }

    if (state.mode === 'companion') {
        return `I'm here with you. Right now I'm handling ${update.stage}, and next I'm going to use ${update.nextCommand}.`;
    }

    return `I'm on ${update.stage} now, and I'll handle it step by step. Next up: ${update.nextCommand}.`;
}

export function buildCompanionReply(state, event) {
    const text = (event.text || '').trim();

    if (isChineseLanguage(state.language)) {
        if (state.mode === 'task') return `收到：${text}`;
        if (/[？?]$/.test(text)) return `我在这里。${text}我会继续专心陪你。`;
        return `我陪着你。${text}`;
    }

    if (state.mode === 'task') {
        return `Status noted: ${text}`;
    }

    if (text.endsWith('?')) {
        return `I'm here with you. ${text} I'm staying focused and keeping us moving.`;
    }

    return `I'm with you. ${text}`;
}

export function buildTaskFailureNarration(state, update) {
    if (isChineseLanguage(state.language)) {
        if (state.mode === 'task') return `任务遇到问题：${update.stage}。执行${update.failedCommand}失败，原因：${update.reason}`;
        return `${update.stage}在执行${update.failedCommand}时遇到问题，但我会调整策略再试一次。原因：${update.reason}`;
    }
    if (state.mode === 'task') {
        return `Task issue: ${update.stage}. Failed at ${update.failedCommand}. Reason: ${update.reason}`;
    }

    return `I'm still with you. ${update.stage} hit a snag on ${update.failedCommand}, but I'll adjust and try a safer next step. Reason: ${update.reason}`;
}
