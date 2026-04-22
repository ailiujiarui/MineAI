import test from 'node:test';
import assert from 'node:assert/strict';

import { createBuildCoordinator } from '../../src/buildCoordination/buildCoordinator.js';

test('splits a build plan into sections and assigns bots', () => {
  const coordinator = createBuildCoordinator({
    planId: 'house',
    placements: [
      { x: 0, y: 64, z: 0, block: 'minecraft:cobblestone' },
      { x: 5, y: 64, z: 0, block: 'minecraft:cobblestone' },
      { x: 0, y: 65, z: 5, block: 'minecraft:oak_planks' },
      { x: 5, y: 65, z: 5, block: 'minecraft:glass_pane' }
    ]
  });

  const a = coordinator.assignBot('bot-a');
  const b = coordinator.assignBot('bot-b');

  assert.ok(a);
  assert.ok(b);
  assert.notEqual(a?.sectionId, b?.sectionId);
});

test('claims placements without collisions and tracks completion', () => {
  const coordinator = createBuildCoordinator({
    planId: 'tower',
    placements: [
      { x: 0, y: 64, z: 0, block: 'minecraft:stone' },
      { x: 1, y: 64, z: 0, block: 'minecraft:stone' }
    ]
  });

  coordinator.assignBot('bot-a');
  coordinator.assignBot('bot-b');

  const first = coordinator.claimNextPlacement('bot-a');
  const second = coordinator.claimNextPlacement('bot-b');

  assert.ok(first);
  assert.ok(second);
  assert.notDeepEqual(first, second);

  coordinator.markPlacementComplete('bot-a', first.key);
  coordinator.markPlacementComplete('bot-b', second.key);
  assert.equal(coordinator.getProgress().completed, 2);
});

test('rebalances an idle bot onto the busiest incomplete section', () => {
  const coordinator = createBuildCoordinator({
    planId: 'wall',
    placements: [
      { x: 0, y: 64, z: 0, block: 'minecraft:stone' },
      { x: 1, y: 64, z: 0, block: 'minecraft:stone' },
      { x: 2, y: 64, z: 0, block: 'minecraft:stone' },
      { x: 3, y: 64, z: 0, block: 'minecraft:stone' }
    ]
  });

  coordinator.assignBot('bot-a');
  const rebalance = coordinator.rebalanceBot('bot-b');

  assert.ok(rebalance);
  assert.equal(typeof rebalance?.sectionId, 'number');
});
