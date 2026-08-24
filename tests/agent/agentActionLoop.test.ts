import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentActionLoop } from '../../src/agent/execution/agentActionLoop.js';

const observation = (iteration: number) => ({ iteration, bounded: true });

test('executes at most one command per iteration and stops when planner is done', async () => {
    const executed: string[] = [];
    const loop = new AgentActionLoop({
        observe: ({ iteration }) => observation(iteration),
        plan: ({ iteration }) => iteration === 0 ? { command: '!stats' } : { done: true },
        execute: async (command) => { executed.push(command); return { outcome: 'success', result: 'ok' }; }
    });
    const result = await loop.run();
    assert.deepEqual(executed, ['!stats']);
    assert.equal(result.stoppedReason, 'completed');
    assert.equal(result.iterations, 2);
});

test('enforces the iteration bound', async () => {
    let calls = 0;
    const commands = ['!stats', '!inventory', '!help'];
    const result = await new AgentActionLoop({
        maxIterations: 3,
        observe: () => ({}),
        plan: ({ iteration }) => ({ command: commands[iteration] }),
        execute: async () => { calls += 1; return { outcome: 'success' }; }
    }).run();
    assert.equal(calls, 3);
    assert.equal(result.stoppedReason, 'max_iterations');
});

test('rejects repeated commands before executing them', async () => {
    let calls = 0;
    const result = await new AgentActionLoop({
        observe: () => ({}),
        plan: () => ({ command: '!stats' }),
        execute: async () => { calls += 1; return { outcome: 'success' }; }
    }).run();
    assert.equal(calls, 1);
    assert.equal(result.stoppedReason, 'repeated_command');
});

test('cancellation prevents later iterations', async () => {
    const controller = new AbortController();
    let calls = 0;
    const result = await new AgentActionLoop({
        signal: controller.signal,
        observe: () => ({}),
        plan: () => ({ command: '!stats' }),
        execute: async () => { calls += 1; controller.abort(); return { outcome: 'success' }; }
    }).run();
    assert.equal(calls, 1);
    assert.equal(result.stoppedReason, 'cancelled');
});

test('permission and confirmation outcomes stop replanning', async () => {
    for (const outcome of ['denied', 'confirmation-required']) {
        let calls = 0;
        const result = await new AgentActionLoop({
            observe: () => ({}),
            plan: () => ({ command: '!stats' }),
            execute: async () => { calls += 1; return { outcome }; }
        }).run();
        assert.equal(calls, 1);
        assert.equal(result.stoppedReason, outcome === 'denied' ? 'permission_denied' : 'confirmation_required');
    }
});

test('planner failures return prior results without throwing', async () => {
    let iteration = 0;
    const result = await new AgentActionLoop({
        observe: () => ({}),
        plan: () => iteration++ === 0 ? { command: '!stats' } : Promise.reject(new Error('planner unavailable')),
        execute: async () => ({ outcome: 'success', result: 'ok' })
    }).run();
    assert.equal(result.stoppedReason, 'planner_error');
    assert.equal(result.results.length, 1);
    assert.match(result.error || '', /planner unavailable/);
});

test('keeps the emergency stop terminal even with a custom executor', async () => {
    let calls = 0;
    const result = await new AgentActionLoop({
        observe: () => ({}),
        plan: () => ({ command: '!stop' }),
        execute: async () => { calls += 1; return { outcome: 'success' }; }
    }).run();
    assert.equal(calls, 1);
    assert.equal(result.stoppedReason, 'emergency_stop');
});
