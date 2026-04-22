import test from 'node:test';
import assert from 'node:assert/strict';

import { planMaidCommand } from '../../src/maid/maidCommandPlanner.js';

test('maps harvest work into crop collection command', () => {
  const plan = planMaidCommand({
    maid: {
      modes: ['harvest', 'replant'],
      snapshot: {
        readyCrops: 5,
        emptyFarmland: 0,
        hostileCount: 0,
        inventoryUtilization: 0.2,
        playerDistance: 2
      },
      cropTarget: 'wheat'
    }
  });

  assert.equal(plan?.decision.mode, 'harvest');
  assert.equal(plan?.command, '!collectBlocks("wheat", 5)');
});

test('maps replant work into tillAndSow action command when farmland needs reseeding', () => {
  const plan = planMaidCommand({
    maid: {
      modes: ['replant'],
      snapshot: {
        readyCrops: 0,
        emptyFarmland: 3,
        hostileCount: 0,
        inventoryUtilization: 0.1,
        playerDistance: 1
      },
      replantTarget: {
        x: 10,
        y: 63,
        z: -4,
        seedType: 'wheat_seeds'
      }
    }
  });

  assert.equal(plan?.decision.mode, 'replant');
  assert.equal(plan?.command, '!tillAndSow(10, 63, -4, "wheat_seeds")');
});

test('maps store-items and defend into existing action commands', () => {
  const storePlan = planMaidCommand({
    maid: {
      modes: ['store-items'],
      snapshot: {
        readyCrops: 0,
        emptyFarmland: 0,
        hostileCount: 0,
        inventoryUtilization: 0.95,
        playerDistance: 1
      },
      storeItemName: 'wheat',
      hasNearbyChest: true
    }
  });

  const defendPlan = planMaidCommand({
    maid: {
      modes: ['defend'],
      snapshot: {
        readyCrops: 0,
        emptyFarmland: 0,
        hostileCount: 2,
        inventoryUtilization: 0.2,
        playerDistance: 1
      }
    }
  });

  assert.equal(storePlan?.command, '!putInChest("wheat", 64)');
  assert.equal(defendPlan?.command, '!attack("hostile")');
});

test('prefers storing harvested crops before replanting when both are possible', () => {
  const plan = planMaidCommand({
    maid: {
      modes: ['harvest', 'replant', 'store-items'],
      snapshot: {
        readyCrops: 0,
        emptyFarmland: 3,
        hostileCount: 0,
        inventoryUtilization: 0.6,
        playerDistance: 1
      },
      storeItemName: 'wheat',
      hasNearbyChest: true,
      replantTarget: {
        x: 10,
        y: 63,
        z: -4,
        seedType: 'wheat_seeds'
      }
    }
  });

  assert.equal(plan?.decision.mode, 'store-items');
  assert.equal(plan?.command, '!putInChest("wheat", 64)');
});
