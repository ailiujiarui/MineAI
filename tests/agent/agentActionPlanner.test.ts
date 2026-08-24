import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentActionPlanner, buildBoundedAgentObservation } from '../../src/agent/execution/agentActionPlanner.js';

test('bounded observation exposes operational summaries, not bot internals', () => {
    const secret = { password: 'should-not-escape' };
    const agent = {
        bot: {
            entity: { position: { x: 1, y: 2, z: 3 } },
            entities: { one: { type: 'player', name: 'Alex' } },
            inventory: { items: () => [{ name: 'stone', count: 4 }] },
            secret
        },
        execution_state_machine: { getState: () => 'IDLE', getSnapshot: () => ({ activeKind: null }) }
    };
    const observation = buildBoundedAgentObservation(agent, 'collect stone');
    assert.deepEqual(observation.position, { x: 1, y: 2, z: 3 });
    assert.equal((observation as any).bot, undefined);
    assert.equal(JSON.stringify(observation).includes('should-not-escape'), false);
});

test('planner returns one command or done without executing it', async () => {
    const calls: unknown[] = [];
    const planner = new AgentActionPlanner({
        prompter: { promptConvo: async (messages: unknown) => { calls.push(messages); return '!stats'; } }
    });
    const result = await planner.plan({ observation: { health: 20 }, goal: 'inspect' });
    assert.deepEqual(result, { command: '!stats' });
    assert.equal(calls.length, 1);
});
