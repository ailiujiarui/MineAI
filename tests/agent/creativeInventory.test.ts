import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canProvisionCreative,
  materializeCreativeItem,
  planCreativeMaterialization,
  withTemporaryCreativeItem
} from '../../src/agent/library/creativeInventory.js';
import { getCommand } from '../../src/agent/commands/index.js';
import * as skills from '../../src/agent/library/skills.js';

function createItem(name, count = 1) {
  const types = { coal: 1, stone: 2, diamond_sword: 3 };
  if (!types[name]) throw new Error('unknown item');
  return { name, type: types[name], count, metadata: 0, stackSize: name === 'diamond_sword' ? 1 : 64 };
}

function makeBot(overrides: any = {}) {
  const slots = Array(46).fill(null);
  const writes: any[] = [];
  const bot: any = {
    game: { gameMode: 'creative' },
    restrict_to_inventory: false,
    quickBarSlot: 0,
    inventory: {
      slots,
      inventoryStart: 9,
      inventoryEnd: 45,
      hotbarStart: 36,
      findInventoryItem(name: string) {
        return slots.find((item) => item?.name === name) || null;
      }
    },
    creative: {
      async setInventorySlot(slot: number, item: any) {
        writes.push({ slot, item });
        slots[slot] = item;
      }
    },
    setQuickBarSlot(slot: number) { bot.quickBarSlot = slot; },
    writes
  };
  Object.assign(bot, overrides);
  return bot;
}

test('creative provisioning policy honors game mode and inventory restriction', () => {
  assert.equal(canProvisionCreative(makeBot()), true);
  assert.equal(canProvisionCreative(makeBot({ restrict_to_inventory: true })), false);
  assert.equal(canProvisionCreative(makeBot({ game: { gameMode: 'survival' } })), false);
});

test('materialization preflights count and inventory capacity without writes', async () => {
  const restricted = makeBot({ restrict_to_inventory: true });
  assert.equal((await materializeCreativeItem(restricted, 'coal', 1, { createItem })).reason, 'restricted');
  assert.equal(restricted.writes.length, 0);

  const full = makeBot();
  for (let slot = 9; slot < 45; slot += 1) full.inventory.slots[slot] = createItem('stone', 64);
  const result = await materializeCreativeItem(full, 'coal', 1, { createItem });
  assert.equal(result.reason, 'inventory-full');
  assert.equal(full.writes.length, 0);
});

test('materialization merges and splits stacks using ordinary inventory first', async () => {
  const bot = makeBot();
  bot.inventory.slots[9] = createItem('coal', 60);
  const plan = planCreativeMaterialization(bot, 'coal', 70, createItem);
  assert.equal(plan.ok, true);
  assert.deepEqual(plan.writes.map((write: any) => [write.slot, write.item.count]), [[9, 64], [10, 64], [11, 2]]);

  const result = await materializeCreativeItem(bot, 'coal', 70, { createItem });
  assert.equal(result.ok, true);
  assert.equal(bot.inventory.slots[9].count, 64);
  assert.equal(bot.inventory.slots[10].count, 64);
  assert.equal(bot.inventory.slots[11].count, 2);
});

test('temporary item lease restores slot and selected hotbar after callback failure', async () => {
  const bot = makeBot();
  const original = createItem('stone', 12);
  for (let slot = 9; slot < 45; slot += 1) bot.inventory.slots[slot] = createItem('stone', 64);
  bot.inventory.slots[37] = original;

  await assert.rejects(
    withTemporaryCreativeItem(bot, 'diamond_sword', async () => {
      bot.quickBarSlot = 2;
      throw new Error('action failed');
    }, { createItem }),
    /action failed/
  );
  assert.equal(bot.inventory.slots[37], original);
  assert.equal(bot.quickBarSlot, 0);
  assert.deepEqual(bot.writes.map((write: any) => write.slot), [37, 37]);
});

test('temporary lease reuses a physical item without creative writes', async () => {
  const bot = makeBot();
  const sword = createItem('diamond_sword', 1);
  bot.inventory.slots[12] = sword;
  const result = await withTemporaryCreativeItem(bot, 'diamond_sword', async (item, temporary) => ({ item, temporary }), { createItem });
  assert.equal(result.item, sword);
  assert.equal(result.temporary, false);
  assert.equal(bot.writes.length, 0);
});

test('temporary lease uses a non-active hotbar slot even when normal inventory is empty', async () => {
  const bot = makeBot();
  await withTemporaryCreativeItem(bot, 'diamond_sword', async (_item, temporary) => {
    assert.equal(temporary, true);
    assert.equal(bot.quickBarSlot, 1);
  }, { createItem });
  assert.deepEqual(bot.writes.map((write: any) => write.slot), [37, 37]);
  assert.equal(bot.quickBarSlot, 0);
});

test('useToolOn borrows and restores a missing creative tool', async () => {
  const bot = makeBot();
  bot.output = '';
  bot.equip = async (item) => { bot.heldItem = item; };
  let activations = 0;
  bot.activateItem = async () => { activations += 1; };

  const result = await skills.useToolOn(bot, 'diamond_sword', 'nothing', { createItem });

  assert.equal(result, true);
  assert.equal(activations, 1);
  assert.equal(bot.inventory.slots[37], null);
  assert.equal(bot.quickBarSlot, 0);
});

test('empty creative inventory still explains on-demand materialization', () => {
  const inventory = getCommand('!inventory');
  const bot = makeBot();
  const result = inventory.perform({
    bot,
    prompter: { profile: { native_language: 'zh-CN' } }
  });
  assert.match(result, /!creativeItem/);
  assert.match(result, /\u771f\u5b9e\u80cc\u5305/);

  bot.restrict_to_inventory = true;
  const restricted = inventory.perform({ bot, prompter: { profile: { native_language: 'zh-CN' } } });
  assert.doesNotMatch(restricted, /!creativeItem/);
});

test('creative craftable query returns bounded guidance without recipe scanning', () => {
  const craftable = getCommand('!craftable');
  const bot = makeBot();
  bot.inventory.items = () => { throw new Error('must not scan inventory recipes'); };
  const result = craftable.perform({ bot, prompter: { profile: { native_language: 'zh-CN' } } });
  assert.match(result, /!creativeItem/);
});

test('creative collect and craft wrappers preserve explicit skill failure', async () => {
  const bot = makeBot();
  bot.output = '';
  const agent = {
    bot,
    actions: {
      async runAction(_label, actionFn) {
        const value = await actionFn();
        return { success: value !== false, message: bot.output, interrupted: false, timedout: false };
      }
    }
  };
  const collect = await getCommand('!collectBlocks').perform(agent, 'coal_ore', 3);
  const craft = await getCommand('!craftRecipe').perform(agent, 'stick', 2);
  assert.equal(collect.__commandOutcome, 'failed');
  assert.equal(craft.__commandOutcome, 'failed');
});
