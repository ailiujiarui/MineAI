import test from 'node:test';
import assert from 'node:assert/strict';
import { captureSkillSnapshot, classifySkillFailure, formatSkillFeedbackForPrompt } from '../../src/agent/execution/skillContract.js';

test('skill snapshot keeps bounded operational fields only', () => {
    const snapshot = captureSkillSnapshot({
        bot: {
            entity: { position: { x: 1, y: 2, z: 3 } },
            health: 18,
            food: 15,
            inventory: { items: () => [{ name: 'oak_log', count: 3 }] }
        },
        execution_state_machine: { getState: () => 'COLLECTING', getSnapshot: () => ({ activeKind: 'action:collectBlocks' }) },
        secret: 'must-not-leak'
    });
    assert.deepEqual(snapshot, {
        position: { x: 1, y: 2, z: 3 },
        health: 18,
        food: 15,
        inventory: { oak_log: 3 },
        executionState: 'COLLECTING',
        actionKind: 'action:collectBlocks'
    });
});

test('skill failure classification uses stable reasons', () => {
    assert.equal(classifySkillFailure({ timedout: true }), 'timeout');
    assert.equal(classifySkillFailure({ interrupted: true }), 'interrupted');
    assert.equal(classifySkillFailure({ success: false, message: 'Inventory full' }), 'inventory_full');
    assert.equal(classifySkillFailure({ success: false, message: 'No oak_log nearby' }), 'target_missing');
    assert.equal(classifySkillFailure({ success: false, message: 'path failed' }), 'path_failed');
});

test('prompt feedback is bounded and marked as observed data', () => {
    const records: any[] = Array.from({ length: 8 }, (_, index) => ({
        actionLabel: `action:${index}`,
        success: false,
        failureReason: 'path_failed',
        durationMs: 10,
        message: 'x'.repeat(500),
        after: { executionState: 'RECOVERING' }
    }));
    const prompt = formatSkillFeedbackForPrompt(records, 8);
    assert.match(prompt, /客观技能执行反馈/);
    assert.doesNotMatch(prompt, /action:0/);
    assert.match(prompt, /action:7/);
    assert.ok(prompt.length < 1400);
});
