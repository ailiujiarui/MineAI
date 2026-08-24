import test from 'node:test';
import assert from 'node:assert/strict';
import { ActionManager } from '../../src/agent/action_manager.js';

function createAgent(feedback: any[]) {
    return {
        bot: { interrupt_code: false, output: '', emit: () => {}, entity: { position: { x: 0, y: 64, z: 0 } } },
        clearBotLogs() {},
        requestInterrupt() {},
        isIdle() { return true; },
        self_prompter: { isActive() { return false; } },
        async recordSkillFeedback(value: any) { feedback.push(value); }
    } as any;
}

test('ActionManager records structured success feedback without changing result', async () => {
    const feedback: any[] = [];
    const manager = new ActionManager(createAgent(feedback));
    const result = await manager.runAction('action:noop', async () => true);
    assert.equal(result.success, true);
    assert.equal(feedback.length, 1);
    assert.equal(feedback[0].failureReason, 'none');
    assert.equal(feedback[0].before.position.x, 0);
    assert.equal(result.feedback.actionId, feedback[0].actionId);
});

test('feedback consumer failure does not fail the action', async () => {
    const agent = createAgent([]);
    agent.recordSkillFeedback = async () => { throw new Error('telemetry unavailable'); };
    const manager = new ActionManager(agent);
    const result = await manager.runAction('action:noop', async () => true);
    assert.equal(result.success, true);
});
