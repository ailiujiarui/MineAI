import test from 'node:test';
import assert from 'node:assert/strict';
import { SkillExperienceStore } from '../../src/agent/execution/skillExperienceStore.js';

test('skill experience store keeps a bounded compact record', () => {
    const store = new SkillExperienceStore(`test-experience-${Date.now()}`);
    const record = store.record({
        actionId: 'a1', actionLabel: 'action:collectBlocks', startedAt: 1, durationMs: 20,
        success: true, failureReason: 'none',
        before: { executionState: 'COLLECTING' }, after: { executionState: 'IDLE' },
        message: 'private chat must not be persisted'
    });
    assert.deepEqual(record, {
        actionId: 'a1', actionLabel: 'action:collectBlocks', startedAt: 1, durationMs: 20,
        success: true, failureReason: 'none', beforeState: 'COLLECTING', afterState: 'IDLE'
    });
    assert.equal(store.getRecent(1)[0].message, undefined);
});
