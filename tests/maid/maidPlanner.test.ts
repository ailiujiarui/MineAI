import test from 'node:test';
import assert from 'node:assert/strict';

import { createMaidTaskRuntime, selectMaidWork } from '../../src/maid/maidPlanner.js';

test('prioritizes defend when danger is present', () => {
  const decision = selectMaidWork({
    modes: ['harvest', 'replant', 'defend'],
    snapshot: {
      readyCrops: 12,
      emptyFarmland: 6,
      hostileCount: 3,
      inventoryUtilization: 0.2,
      playerDistance: 3
    }
  });

  assert.equal(decision.mode, 'defend');
  assert.match(decision.reason, /hostile/i);
});

test('picks harvest before replant and store-items when crops are ready', () => {
  const decision = selectMaidWork({
    modes: ['harvest', 'replant', 'store-items'],
    snapshot: {
      readyCrops: 8,
      emptyFarmland: 4,
      hostileCount: 0,
      inventoryUtilization: 0.4,
      playerDistance: 10
    }
  });

  assert.equal(decision.mode, 'harvest');
});

test('runtime advances through assigned work lifecycle', () => {
  const runtime = createMaidTaskRuntime();
  runtime.assign({
    mode: 'follow',
    reason: 'player is far away',
    target: 'player'
  });

  assert.equal(runtime.getState().status, 'running');
  runtime.markBlocked('water stream');
  assert.equal(runtime.getState().status, 'blocked');
  runtime.markComplete();
  assert.equal(runtime.getState().status, 'complete');
});
