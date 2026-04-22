import test from 'node:test';
import assert from 'node:assert/strict';

import { createModKnowledgeIndex } from '../../src/modKnowledge/knowledgeIndex.js';

test('indexes mod metadata, registry summaries, and override sources', () => {
  const index = createModKnowledgeIndex({
    mods: [
      {
        id: 'touhou_little_maid',
        name: 'Touhou Little Maid',
        version: '1.0.0',
        loader: 'forge',
        description: 'Maid helpers and work modes'
      }
    ],
    registries: {
      items: [{ id: 'touhou_little_maid:photo', name: 'Photo', sourceMod: 'touhou_little_maid' }],
      blocks: [{ id: 'minecraft:wheat', name: 'Wheat', sourceMod: 'minecraft' }],
      entities: [{ id: 'touhou_little_maid:maid', name: 'Maid', sourceMod: 'touhou_little_maid' }],
      recipes: [{ id: 'crafttweaker:maid_recipe', name: 'Custom Maid Recipe', sourceMod: 'touhou_little_maid' }]
    },
    overrides: [
      {
        id: 'kubejs_server',
        kind: 'kubejs',
        path: 'kubejs/server_scripts/main.js',
        description: 'Server-side pack logic'
      }
    ]
  });

  const maidResults = index.search('maid');
  assert.equal(maidResults.length, 4);
  assert.equal(index.findMod('touhou_little_maid')?.name, 'Touhou Little Maid');
  assert.equal(index.listOverrideSources()[0].kind, 'kubejs');
});

test('supports kind-filtered search and unknown misses', () => {
  const index = createModKnowledgeIndex({
    mods: [],
    registries: {
      items: [{ id: 'minecraft:iron_ingot', name: 'Iron Ingot', sourceMod: 'minecraft' }],
      blocks: [{ id: 'minecraft:iron_ore', name: 'Iron Ore', sourceMod: 'minecraft' }],
      entities: [],
      recipes: []
    },
    overrides: []
  });

  assert.equal(index.search('iron', 'item').length, 1);
  assert.equal(index.search('iron', 'block').length, 1);
  assert.equal(index.search('diamond').length, 0);
});
