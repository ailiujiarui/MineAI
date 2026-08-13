import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { StateMachineExecutionAdapter } from '../../src/agent/execution/stateMachineAdapter.js';

class FakeBot extends EventEmitter {}

test('disabled adapter preserves direct execution and remains idle', async () => {
    const adapter = new StateMachineExecutionAdapter(new FakeBot(), { enabled: false });
    const result = await adapter.submit({ actionId: 'a', kind: 'noop', run: async () => 7 });
    assert.equal(result, 7);
    assert.equal(adapter.getState(), 'IDLE');
});

test('enabled adapter verifies successful action', async () => {
    const adapter = new StateMachineExecutionAdapter(new FakeBot(), { enabled: true });
    const result = await adapter.submit({ actionId: 'a', kind: 'noop', run: async () => 7, verify: () => true });
    assert.equal(result, 7);
    assert.equal(adapter.getState(), 'IDLE');
});

test('stop rejects in-flight action and invokes interrupt once', async () => {
    let release!: () => void;
    let interrupted = 0;
    const adapter = new StateMachineExecutionAdapter(new FakeBot(), { enabled: true, requestInterrupt: () => interrupted++ });
    const pending = adapter.submit({ actionId: 'a', kind: 'wait', run: () => new Promise(resolve => { release = () => resolve(true); }) });
    await adapter.stop();
    await assert.rejects(pending, /stopped/);
    release();
    assert.equal(interrupted, 1);
  assert.equal(adapter.getState(), 'IDLE');
});

test('failed postcondition recovers and clears the active snapshot', async () => {
    const adapter = new StateMachineExecutionAdapter(new FakeBot(), { enabled: true });
    await assert.rejects(
        adapter.submit({ actionId: 'failed', kind: 'noop', run: async () => 1, verify: () => false }),
        /postcondition failed/
    );
    assert.deepEqual(adapter.getSnapshot(), { state: 'IDLE', activeActionId: null, activeKind: null });
});

test('classifies follow, collection, and evade actions into explicit states', async () => {
    const bot = new FakeBot();
    const adapter = new StateMachineExecutionAdapter(bot, { enabled: true });
    for (const [kind, expected] of [['action:followPlayer', 'FOLLOWING'], ['action:collectBlocks', 'COLLECTING'], ['action:moveAway', 'EVADE']] as const) {
        let release!: () => void;
        const pending = adapter.submit({ actionId: kind, kind, run: () => new Promise(resolve => { release = () => resolve(true); }) });
        assert.equal(adapter.getState(), expected);
        release();
        await pending;
        assert.equal(adapter.getState(), 'IDLE');
    }
});

test('recovery is explicit and returns to idle after the recovery task', async () => {
    const adapter = new StateMachineExecutionAdapter(new FakeBot(), { enabled: true });
    let activeState = '';
    await adapter.recover(async () => { activeState = adapter.getState(); });
    assert.equal(activeState, 'RECOVERING');
    assert.equal(adapter.getState(), 'IDLE');
});
