import { readFile } from 'node:fs/promises';
import { AgentActionLoop, type AgentActionLoopResult } from './agentActionLoop.js';
import { commandExists, containsCommand, parseCommandMessage } from '../commands/index.js';

const MAX_STEPS = 20;
const MAX_TEXT = 500;

export interface ReplayStep {
    observation?: unknown;
    plan: { command?: string; done?: boolean } | string | null;
    execution?: any;
    feedback?: any;
}

export interface OfflineReplayCase {
    name: string;
    maxIterations: number;
    steps: ReplayStep[];
    assertions?: {
        expectedStopReason?: string;
        expectedCommands?: string[];
        maxExecutedCommands?: number;
    };
    cancelAtIteration?: number;
}

export interface OfflineReplayReport {
    name: string;
    passed: boolean;
    assertions: Record<string, { passed: boolean; expected?: unknown; actual?: unknown }>;
    result: AgentActionLoopResult;
}

function text(value: unknown): string {
    return String(value ?? '').replace(/[\r\n]+/g, ' ').slice(0, MAX_TEXT);
}

function normalizePlan(value: any): ReplayStep['plan'] {
    if (value === null || typeof value === 'string') return value === null ? null : text(value);
    if (!value || typeof value !== 'object') return null;
    return {
        ...(typeof value.command === 'string' ? { command: text(value.command) } : {}),
        ...(value.done === true ? { done: true } : {})
    };
}

export function loadReplayCase(value: unknown): OfflineReplayCase {
    if (!value || typeof value !== 'object') throw new Error('Replay case must be an object.');
    const input: any = value;
    if (!Array.isArray(input.steps) || input.steps.length === 0 || input.steps.length > MAX_STEPS) {
        throw new Error(`Replay case steps must contain 1-${MAX_STEPS} items.`);
    }
    return {
        name: text(input.name || 'unnamed-replay'),
        maxIterations: Math.max(1, Math.min(MAX_STEPS, Math.floor(Number(input.maxIterations) || 3))),
        cancelAtIteration: Number.isInteger(input.cancelAtIteration) ? input.cancelAtIteration : undefined,
        steps: input.steps.map((step: any) => ({
            observation: step?.observation ?? {},
            plan: normalizePlan(step?.plan),
            execution: step?.execution,
            feedback: step?.feedback
        })),
        assertions: input.assertions && typeof input.assertions === 'object' ? {
            expectedStopReason: text(input.assertions.expectedStopReason) || undefined,
            expectedCommands: Array.isArray(input.assertions.expectedCommands)
                ? input.assertions.expectedCommands.slice(0, MAX_STEPS).map(text)
                : undefined,
            maxExecutedCommands: Number.isFinite(input.assertions.maxExecutedCommands)
                ? Math.max(0, Math.floor(input.assertions.maxExecutedCommands))
                : undefined
        } : undefined
    };
}

function validateReplayCommand(command: string): string | null {
    const name = containsCommand(command);
    if (!name || !commandExists(name)) return '命令不存在或格式无效。';
    try {
        const parsed = parseCommandMessage(command);
        return typeof parsed === 'string' ? parsed : null;
    } catch {
        return null;
    }
}

export async function runOfflineReplay(replayCase: OfflineReplayCase): Promise<OfflineReplayReport> {
    let cancelled = false;
    let currentStep = 0;
    const controller = new AbortController();
    const loop = new AgentActionLoop({
        maxIterations: replayCase.maxIterations,
        signal: controller.signal,
        observe: ({ iteration }) => {
            currentStep = iteration;
            if (replayCase.cancelAtIteration === iteration) {
                cancelled = true;
                controller.abort();
            }
            return replayCase.steps[iteration]?.observation ?? {};
        },
        plan: ({ iteration }) => replayCase.steps[iteration]?.plan ?? { done: true },
        execute: async (_command, context) => replayCase.steps[context.iteration]?.execution ?? { outcome: 'success', result: 'replayed' },
        getSkillFeedback: () => replayCase.steps[currentStep]?.feedback ? [replayCase.steps[currentStep].feedback] : [],
        validateCommand: validateReplayCommand
    });
    const result = await loop.run();
    const assertions: OfflineReplayReport['assertions'] = {};
    const expectedReason = replayCase.assertions?.expectedStopReason;
    if (expectedReason) assertions.expectedStopReason = { passed: result.stoppedReason === expectedReason, expected: expectedReason, actual: result.stoppedReason };
    const expectedCommands = replayCase.assertions?.expectedCommands;
    if (expectedCommands) assertions.expectedCommands = { passed: JSON.stringify(result.commands) === JSON.stringify(expectedCommands), expected: expectedCommands, actual: result.commands };
    const maxCommands = replayCase.assertions?.maxExecutedCommands;
    if (maxCommands !== undefined) assertions.maxExecutedCommands = { passed: result.commands.length <= maxCommands, expected: maxCommands, actual: result.commands.length };
    if (replayCase.cancelAtIteration !== undefined) assertions.cancelled = { passed: cancelled && result.stoppedReason === 'cancelled', expected: true, actual: result.stoppedReason === 'cancelled' };
    return { name: replayCase.name, passed: Object.values(assertions).every(assertion => assertion.passed), assertions, result };
}

export async function runOfflineReplayFile(filePath: string): Promise<OfflineReplayReport> {
    const raw = JSON.parse(await readFile(filePath, 'utf8'));
    return runOfflineReplay(loadReplayCase(raw));
}
