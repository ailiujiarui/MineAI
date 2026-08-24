import test from 'node:test';
import assert from 'node:assert/strict';
import { TaskMetrics } from '../../src/agent/execution/taskMetrics.js';

test('task metrics records bounded action and curriculum data', () => {
    const metrics = new TaskMetrics(`test-metrics-${Date.now()}`);
    metrics.recordFeedback({ success: true });
    metrics.recordFeedback({ success: false, failureReason: 'path_failed' });
    metrics.recordFeedback({ success: false, failureReason: 'timeout' });
    metrics.recordStop(42.4);
    metrics.recordStage('gather_wood');
    metrics.recordStage('gather_wood');
    metrics.recordStage('craft_wooden_pickaxe');
    const snapshot = metrics.snapshot();
    assert.equal(snapshot.actions, 3);
    assert.equal(snapshot.successes, 1);
    assert.equal(snapshot.failures, 2);
    assert.equal(snapshot.failureReasons.path_failed, 1);
    assert.equal(snapshot.recoveries, 1);
    assert.deepEqual(snapshot.stopLatencyMs, [42]);
    assert.deepEqual(snapshot.stageTransitions.map((entry: any) => entry.stage), ['gather_wood', 'craft_wooden_pickaxe']);
});
