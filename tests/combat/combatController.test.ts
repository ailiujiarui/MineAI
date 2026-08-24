import test from 'node:test';
import assert from 'node:assert/strict';
import { CombatController, selectCombatTarget } from '../../src/combat/combatController.js';

const entities = [
  { entityId: 2, type: 'hostile' as const, name: 'zombie', distance: 8, threat: 2 },
  { entityId: 1, type: 'hostile' as const, name: 'skeleton', distance: 14, threat: 1, attackingPlayer: true },
];

test('target selection prioritizes threats attacking the protected player', () => {
  assert.equal(selectCombatTarget({ health: 20, food: 20, hostileCount: 2, nearbyEntities: entities, playerUnderAttack: true })?.entityId, 1);
});

test('controller progresses through approach, attack and recovery', () => {
  const controller = new CombatController({ maxCombatMs: 10000 });
  const snapshot: any = { health: 20, food: 20, hostileCount: 1, combatMode: 'vanilla', nearbyEntities: [{ entityId: 4, type: 'hostile', name: 'zombie', distance: 2 }] };
  assert.equal(controller.tick(snapshot, { task: 'follow' })?.kind, 'select_target');
  assert.equal(controller.tick(snapshot)?.kind, 'move');
  assert.equal(controller.tick(snapshot)?.kind, 'look');
  assert.equal(controller.tick(snapshot)?.kind, 'attack');
  snapshot.nearbyEntities = [];
  assert.equal(controller.tick(snapshot)?.kind, 'resume_task');
  assert.deepEqual(controller.getTaskContext(), { task: 'follow' });
});

test('low health enters retreat and Epic Fight uses a skill phase', () => {
  const controller = new CombatController();
  const snapshot: any = { health: 20, food: 20, hostileCount: 1, combatMode: 'epicfight', nearbyEntities: [{ entityId: 2, type: 'hostile', name: 'pillager', distance: 2 }] };
  controller.tick(snapshot); controller.tick(snapshot); controller.tick(snapshot); controller.tick(snapshot);
  assert.equal(controller.tick(snapshot)?.kind, 'use_skill');
  snapshot.health = 5;
  assert.equal(controller.tick(snapshot)?.kind, 'move');
  assert.equal(controller.getState(), 'RETREAT');
});

test('explicit retreat stops even when target is no longer visible', () => {
  const controller = new CombatController();
  const snapshot: any = { health: 20, food: 20, hostileCount: 1, nearbyEntities: [{ entityId: 9, type: 'hostile', name: 'zombie', distance: 2 }] };
  controller.tick(snapshot);
  controller.tick(snapshot);
  controller.requestRetreat();
  snapshot.nearbyEntities = [];
  assert.equal(controller.tick(snapshot)?.kind, 'stop_all');
  assert.equal(controller.getState(), 'RECOVER');
  assert.equal(controller.tick(snapshot), null);
  assert.equal(controller.getState(), 'IDLE');
});

test('lost targets emit resume once and do not repeat recovery commands', () => {
  const controller = new CombatController();
  const snapshot: any = { health: 20, food: 20, hostileCount: 1, nearbyEntities: [{ entityId: 3, type: 'hostile', name: 'spider', distance: 2 }] };
  controller.tick(snapshot, { task: 'harvest' });
  controller.tick(snapshot);
  snapshot.nearbyEntities = [];
  assert.equal(controller.tick(snapshot)?.kind, 'resume_task');
  assert.equal(controller.tick(snapshot), null);
  assert.equal(controller.getState(), 'IDLE');
});
