import { captureSkillSnapshot } from './skillContract.js';

function boundedString(value: unknown, max = 240): string {
    return String(value ?? '').replace(/[\r\n]+/g, ' ').slice(0, max);
}

export function buildBoundedAgentObservation(agent: any, goal: string, iteration = 0) {
    const snapshot = captureSkillSnapshot(agent);
    const entities = (agent?.bot?.entities && typeof agent.bot.entities === 'object')
        ? Object.values(agent.bot.entities).slice(0, 32).map((entity: any) => ({
            type: boundedString(entity?.type, 32),
            name: boundedString(entity?.name || entity?.displayName, 48),
            distance: typeof entity?.position?.distanceTo === 'function' && agent?.bot?.entity?.position
                ? Math.round(entity.position.distanceTo(agent.bot.entity.position) * 10) / 10
                : null
        }))
        : [];
    return {
        iteration,
        goal: boundedString(goal, 300),
        position: snapshot.position,
        health: snapshot.health,
        food: snapshot.food,
        inventory: Object.entries(snapshot.inventory).slice(0, 24),
        executionState: snapshot.executionState,
        actionKind: snapshot.actionKind,
        nearbyEntities: entities
    };
}

export class AgentActionPlanner {
    private readonly agent: any;

    constructor(agent: any) {
        this.agent = agent;
    }

    observe({ iteration, goal }: { iteration: number; goal: string }) {
        return buildBoundedAgentObservation(this.agent, goal, iteration);
    }

    async plan(input: { observation: unknown; goal: string }) {
        const prompt = [
            `当前主动任务目标：${boundedString(input.goal, 300)}`,
            '以下是客观、有限的环境观察：',
            JSON.stringify(input.observation),
            '请只返回下一步一个 Minecraft 命令（例如 !commandName("arg")），或返回 done。不要解释。'
        ].join('\n');
        const response = await this.agent.prompter.promptConvo([{ role: 'system', content: prompt }]);
        const text = boundedString(response, 500).trim();
        if (!text || /^done\.?$/i.test(text)) return { done: true };
        return { command: text };
    }
}
