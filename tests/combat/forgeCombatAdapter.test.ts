import test from 'node:test';
import assert from 'node:assert/strict';
import { ForgeCombatAdapter, mapCombatAction } from '../../src/combat/forgeCombatAdapter.js';

const snapshot: any = {
  player: { position: { x: 0, y: 2, z: 0 } },
  nearbyEntities: [{ entityId: 7, name: 'zombie', type: 'hostile', distance: 2, position: { x: 1, y: 2, z: 3 } }]
};

test('maps combat actions only to structured bridge actions', () => {
  assert.deepEqual(mapCombatAction({ kind: 'attack', targetEntityId: 7, mode: 'tap' }, snapshot), [{ kind: 'attack', mode: 'tap', targetEntityId: 7 }]);
  assert.deepEqual(mapCombatAction({ kind: 'attack', targetEntityId: 999, mode: 'tap' }, snapshot), []);
  assert.deepEqual(mapCombatAction({ kind: 'use_skill', skillSlot: 'weapon_skill_2' }, snapshot), [{ kind: 'use_skill', skillSlot: 'weapon_skill_2' }]);
  assert.deepEqual(mapCombatAction({ kind: 'select_target', targetEntityId: 7 }, snapshot), []);
  assert.equal((mapCombatAction({ kind: 'look', targetEntityId: 7 }, snapshot)[0] as any).kind, 'look');
});

test('adapter fails closed on stale or unsupported clients', async () => {
  const unavailable = new ForgeCombatAdapter({ getClient: () => null }, { clientId: 'x' });
  assert.equal((await unavailable.execute({ kind: 'attack', targetEntityId: 7 }, snapshot, 'c1')).status, 'unavailable');
  const unsupported = new ForgeCombatAdapter({ getClient: () => ({ hello: { payload: { modCapabilities: { epicFight: false } } }, snapshot: { payload: snapshot } }) }, { clientId: 'x', requireEpicFight: true });
  assert.equal((await unsupported.execute({ kind: 'use_skill' }, snapshot, 'c2')).status, 'unsupported');
});

test('adapter waits for bridge acknowledgement', async () => {
  const calls: any[] = [];
  const adapter = new ForgeCombatAdapter({
    getClient: () => ({ hello: { payload: { modCapabilities: { epicFight: true } } }, snapshot: { payload: snapshot } }),
    sendCommandAndWaitForAck: async (_id: string, command: any) => { calls.push(command); return { payload: { status: 'ok', commandId: command.payload.id } }; }
  }, { clientId: 'x' });
  const result = await adapter.execute({ kind: 'attack', targetEntityId: 7 }, snapshot, 'c3');
  assert.equal(result.status, 'ok');
  assert.equal(calls[0].payload.actions[0].kind, 'attack');
});

test('adapter fails closed for skills without Epic Fight and stale snapshots', async () => {
  const baseClient = {
    hello: { payload: { modCapabilities: { epicFight: false } } },
    snapshot: { payload: snapshot }
  };
  const adapter = new ForgeCombatAdapter({ getClient: () => baseClient }, { clientId: 'x' });
  assert.equal((await adapter.execute({ kind: 'use_skill' }, snapshot, 'skill-1')).status, 'unsupported');

  const staleAdapter = new ForgeCombatAdapter({
    getClient: () => ({
      hello: { payload: { modCapabilities: { epicFight: true } } },
      snapshot: { payload: snapshot },
      lastSnapshotAt: Date.now() - 10_000
    })
  }, { clientId: 'x', maxSnapshotAgeMs: 100 });
  assert.equal((await staleAdapter.execute({ kind: 'stop_all' }, snapshot, 'stale-1')).status, 'stale_snapshot');
});

test('adapter rejects an acknowledgement for another command', async () => {
  const adapter = new ForgeCombatAdapter({
    getClient: () => ({ hello: { payload: { modCapabilities: { epicFight: true } } }, snapshot: { payload: snapshot } }),
    sendCommandAndWaitForAck: async () => ({ payload: { status: 'ok', commandId: 'other-command' } })
  }, { clientId: 'x' });
  assert.equal((await adapter.execute({ kind: 'attack', targetEntityId: 7 }, snapshot, 'expected-command')).status, 'rejected');
});
