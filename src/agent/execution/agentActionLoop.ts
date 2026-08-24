import {
    COMMAND_OUTCOMES,
    commandExists,
    executeCommandWithOutcome,
    parseCommandMessage,
    truncCommandMessage,
    containsCommand
} from '../commands/index.js';

export type AgentLoopStopReason =
    | 'completed'
    | 'max_iterations'
    | 'cancelled'
    | 'empty_command'
    | 'invalid_command'
    | 'repeated_command'
    | 'planner_error'
    | 'execution_error'
    | 'permission_denied'
    | 'confirmation_required'
    | 'inventory_full'
    | 'emergency_stop';

export interface AgentLoopIteration<Observation = unknown> {
    iteration: number;
    observation: Observation;
    command?: string;
    result?: any;
    feedback?: any;
}

export interface AgentActionLoopOptions<Observation = unknown> {
    agent?: any;
    maxIterations?: number;
    signal?: AbortSignal;
    observe: (context: { iteration: number; history: AgentLoopIteration<Observation>[] }) => Promise<Observation> | Observation;
    plan: (input: {
        observation: Observation;
        iteration: number;
        history: AgentLoopIteration<Observation>[];
    }) => Promise<{ command?: string; done?: boolean } | string | null | undefined> | { command?: string; done?: boolean } | string | null | undefined;
    execute?: (command: string, context: { iteration: number; observation: Observation }) => Promise<any>;
    getSkillFeedback?: (limit: number) => any[];
    executionContext?: { actor?: string; origin?: string; confirmed?: boolean };
    validateCommand?: (command: string) => string | null;
}

export interface AgentActionLoopResult<Observation = unknown> {
    iterations: number;
    commands: string[];
    results: any[];
    feedback: any[];
    stoppedReason: AgentLoopStopReason;
    history: AgentLoopIteration<Observation>[];
    error?: string;
}

function cancelled(signal?: AbortSignal): boolean {
    return signal?.aborted === true;
}

function plannerCommand(plan: any): string | null {
    if (typeof plan === 'string') return plan.trim() || null;
    if (!plan || typeof plan !== 'object' || plan.done === true) return null;
    return typeof plan.command === 'string' ? plan.command.trim() || null : null;
}

function validateCommand(command: string): string | null {
    if (!command.startsWith('!')) return '命令必须以 ! 开头。';
    const name = containsCommand(command);
    if (!name || !commandExists(name)) return '命令不存在或格式无效。';
    const canonical = truncCommandMessage(command);
    if (canonical.length !== command.length && command.slice(canonical.length).trim()) {
        return '每轮只能执行一个命令。';
    }
    const parsed = parseCommandMessage(canonical);
    return typeof parsed === 'string' ? parsed : null;
}

function stopForOutcome(command: string, result: any, feedback: any): AgentLoopStopReason | null {
    if (containsCommand(command) === '!stop' || result?.commandName === '!stop') return 'emergency_stop';
    if (result?.outcome === COMMAND_OUTCOMES.DENIED || result?.outcome === COMMAND_OUTCOMES.PLAYER_ACTION_REQUIRED) return 'permission_denied';
    if (result?.outcome === COMMAND_OUTCOMES.CONFIRMATION_REQUIRED) return 'confirmation_required';
    if (feedback?.failureReason === 'inventory_full') return 'inventory_full';
    if (result?.outcome === COMMAND_OUTCOMES.FAILED || result?.outcome === COMMAND_OUTCOMES.INVALID) return 'execution_error';
    return null;
}

export class AgentActionLoop<Observation = unknown> {
    private readonly options: AgentActionLoopOptions<Observation>;

    constructor(options: AgentActionLoopOptions<Observation>) {
        this.options = options;
    }

    async run(): Promise<AgentActionLoopResult<Observation>> {
        const maxIterations = Math.max(1, Math.min(20, Math.floor(this.options.maxIterations ?? 3)));
        const history: AgentLoopIteration<Observation>[] = [];
        const commands: string[] = [];
        const results: any[] = [];
        const feedback: any[] = [];
        let stoppedReason: AgentLoopStopReason = 'max_iterations';
        let error: string | undefined;

        for (let iteration = 0; iteration < maxIterations; iteration += 1) {
            if (cancelled(this.options.signal)) { stoppedReason = 'cancelled'; break; }
            let observation: Observation;
            try {
                observation = await this.options.observe({ iteration, history });
            } catch (cause: any) {
                stoppedReason = 'planner_error'; error = String(cause?.message || cause); break;
            }
            const entry: AgentLoopIteration<Observation> = { iteration, observation };
            history.push(entry);
            let plan: any;
            try {
                plan = await this.options.plan({ observation, iteration, history });
            } catch (cause: any) {
                stoppedReason = 'planner_error'; error = String(cause?.message || cause); break;
            }
            if (plan && typeof plan === 'object' && plan.done === true) { stoppedReason = 'completed'; break; }
            if (cancelled(this.options.signal)) { stoppedReason = 'cancelled'; break; }
            const command = plannerCommand(plan);
            if (!command) { stoppedReason = 'empty_command'; break; }
            const validationError = this.options.validateCommand
                ? this.options.validateCommand(command)
                : validateCommand(command);
            if (validationError) { stoppedReason = 'invalid_command'; error = validationError; break; }
            if (commands.includes(command)) { stoppedReason = 'repeated_command'; break; }
            entry.command = command;
            commands.push(command);
            let result: any;
            try {
                result = this.options.execute
                    ? await this.options.execute(command, { iteration, observation })
                    : await executeCommandWithOutcome(this.options.agent, command, this.options.executionContext || { origin: 'model' });
            } catch (cause: any) {
                result = { outcome: COMMAND_OUTCOMES.FAILED, result: String(cause?.message || cause) };
            }
            entry.result = result;
            results.push(result);
            const latest = (this.options.getSkillFeedback || this.options.agent?.getSkillFeedback)?.call(this.options.agent, 1)?.[0] || null;
            if (latest) { entry.feedback = latest; feedback.push(latest); }
            const outcomeStop = stopForOutcome(command, result, latest);
            if (outcomeStop) { stoppedReason = outcomeStop; break; }
            if (cancelled(this.options.signal)) { stoppedReason = 'cancelled'; break; }
        }
        return { iterations: history.length, commands, results, feedback, stoppedReason, history, ...(error ? { error } : {}) };
    }
}

export async function runAgentActionLoop<Observation = unknown>(options: AgentActionLoopOptions<Observation>) {
    return new AgentActionLoop(options).run();
}
