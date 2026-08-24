import test from 'node:test';
import assert from 'node:assert/strict';
import { loadReplayCase, runOfflineReplay } from '../../src/agent/execution/offlineReplay.js';

test('offline replay is deterministic and checks command outcomes', async () => {
    const replay = loadReplayCase({
        name: 'success', maxIterations: 2,
        steps: [{ observation: { health: 20 }, plan: { command: '!stats' }, execution: { outcome: 'success', result: 'ok' } }, { plan: { done: true } }],
        assertions: { expectedStopReason: 'completed', expectedCommands: ['!stats'] }
    });
    const first = await runOfflineReplay(replay);
    const second = await runOfflineReplay(replay);
    assert.equal(first.passed, true);
    assert.deepEqual(first.result, second.result);
});

test('offline replay handles repeated commands and cancellation', async () => {
    const repeated = await runOfflineReplay(loadReplayCase({
        name: 'repeat', steps: [{ plan: { command: '!stats' } }, { plan: { command: '!stats' } }],
        assertions: { expectedStopReason: 'repeated_command', maxExecutedCommands: 1 }
    }));
    assert.equal(repeated.passed, true);
    const cancelled = await runOfflineReplay(loadReplayCase({
        name: 'cancel', cancelAtIteration: 0, steps: [{ plan: { command: '!stats' } }],
        assertions: { expectedStopReason: 'cancelled' }
    }));
    assert.equal(cancelled.passed, true);
});

test('offline replay rejects unknown commands', async () => {
    const report = await runOfflineReplay(loadReplayCase({
        name: 'invalid', steps: [{ plan: { command: '!doesNotExist' } }],
        assertions: { expectedStopReason: 'invalid_command', maxExecutedCommands: 0 }
    }));
    assert.equal(report.passed, true);
});
