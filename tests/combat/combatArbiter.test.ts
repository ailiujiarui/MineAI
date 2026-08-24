import test from 'node:test';
import assert from 'node:assert/strict';
import { CombatArbiter } from '../../src/combat/combatArbiter.js';

test('combat arbiter grants one owner and preserves task context', () => {
  const arbiter = new CombatArbiter();
  assert.equal(arbiter.request('autonomy', { task: 'harvest' }), true);
  assert.equal(arbiter.request('voice'), true);
  assert.equal(arbiter.getOwner(), 'voice');
  assert.equal(arbiter.request('autonomy'), false);
  assert.deepEqual(arbiter.resume(), { task: 'harvest' });
  assert.equal(arbiter.getOwner(), 'none');
});

test('system stop clears combat ownership', () => {
  const arbiter = new CombatArbiter();
  arbiter.request('autonomy');
  assert.equal(arbiter.stop().kind, 'stop_all');
  assert.equal(arbiter.getOwner(), 'none');
});

test('retreat transitions the controller before the next tick', () => {
  const arbiter = new CombatArbiter();
  arbiter.request('voice', { task: 'protect' });
  const snapshot: any = { health: 20, food: 20, hostileCount: 1, nearbyEntities: [{ entityId: 5, type: 'hostile', name: 'zombie', distance: 2 }] };
  arbiter.tick(snapshot);
  assert.equal(arbiter.retreat().kind, 'move');
  assert.equal(arbiter.controller.getState(), 'RETREAT');
  assert.equal(arbiter.tick({ ...snapshot, nearbyEntities: [] })?.kind, 'stop_all');
});
