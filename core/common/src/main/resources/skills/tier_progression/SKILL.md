---
name: tier_progression
description: Safe wood → stone → iron → diamond progression with small mining batches, early shields and armor, crafting, smelting and food. Use for mining, survival supplies and equipment preparation; stop at the tier the owner requested.
---

# Skill: tier_progression

Phase 1 of the dragon route. You need diamond tools before you can mine obsidian and survive the Nether. Skip nothing here — under-geared Nether trips end in a lost inventory.

Match the owner's current goal: equipment preparation can stop at iron. The full diamond checklist below applies when preparing for the Nether or the dragon route; finish the requested tier before planning a later phase.

## Done when (verify the current inventory and `numen.status.self` for health and worn gear)

- **Diamond pickaxe** (required for obsidian) and **diamond sword**, equipped as appropriate
- **Iron-or-better armor** worn (full iron is fine; diamond chestplate first if diamonds allow)
- **Bow + 32 arrows** (the dragon's crystals must be shot; blazes are safest shot too)
- **32+ cooked food** (cooked_beef / cooked_porkchop preferred)
- **64+ cobblestone** kept in inventory at all times — navigation spends it when bridging/pillaring (it is in the default `materials` of every walk)

## Tool tier chain

`numen.work.dig` picks the best tool you carry and skips blocks none of them harvests (a too-low tier breaks the block with **no drop**), naming the tier it needs. `numen.scan.block` when unsure.

`numen.work.dig` digs **blocks you hand it** (a cluster from a scan, its Blocks, or cells), not block types: look first — `numen.scan.blocks` for every variant returns the clusters nearest first, each with its `blocks`; then `numen.move.to` the cluster with arrive "dig" (it stands where your hand reaches the most of it), `numen.work.dig` it (`count` caps the cells), and `numen.work.collect()` for the drops — again until none is left; the built-in `numen.work.mine(cluster)` does all of it. Nothing is kept between programs: scan again to see what is left. `numen.work.dig` only digs what your hand reaches **from where you stand** and never walks; it says how many cells are still out of reach and the `numen.move.to` call to copy to get to them (add `costs = {dig = true, place = true}` to its options when the way needs digging).

The same rule gates navigation: **`numen.move.to` only digs through blocks your held tool can harvest.** Descending into stone with a sword in hand fails with "no path" — travel with the pickaxe in your main hand; switch to a weapon only for the fight, then switch back.

## Gather in usable batches

One gathering program should finish one usable batch, then give you a chance to recover, smelt and equip it. `numen.work.mine(vein, {count = 4})` collects up to four target cells instead of clearing the whole vein. Choose the next count from the next useful recipe, rather than collecting an entire future shopping list before using any of it.

- Check `numen.status.self()` before the batch: `hp`, `max_hp`, `hunger`, `air`, `max_air`, `in_water`, `in_lava`. Recover health and food in a protected spot before another trip.
- `numen.work.mine` checks safety between walking, digging and collecting. Falling health, health at most half, low breath, lava or a visible hostile stops it with a structured error. Its `data.dug` and `data.drops` preserve partial progress; the drops may still be on the ground. Reflexes handle immediate threats while a body task is running.
- End the program on a failed batch or no inventory gain. Read the error and change the plan before trying again. Catch an error only to report it and end or take a specific corrective step; catching it and repeating the same mine or walk hides the failure.
- In a safe area, turn the first useful iron batch into an iron pickaxe and a shield; `numen.gear.hold("shield")` puts it in the off hand. Add and wear armor as each piece becomes affordable. Make a bucket when the next trip needs one. Verify items are carried and armor is worn before another collection trip.
- Smelting is complete only after taking the output. `numen.inv.smelt(furnace, "raw_iron", count)` loads, waits, takes and closes. For an unattended furnace, remember its location and set a return timer; collect and use that batch before another long trip.

A short first iron batch, with a real inventory-progress check:

```lua
local me = numen.status.self()
if me.hp < me.max_hp or me.hunger < 18 then
  print("Recover health and food in a protected spot before another mining trip.")
  return
end
local veins = numen.scan.blocks("iron_ore", "deepslate_iron_ore", {radius = 16})
if #veins == 0 then
  print("No iron vein here; choose another area before trying again.")
  return
end
local before = numen.inv.count("raw_iron")
local dug = numen.work.mine(veins[1], {count = 4})
local gained = numen.inv.count("raw_iron") - before
if gained <= 0 then
  raise("failed", "the batch gained no raw iron; reassess before mining again", nil, {dug = dug})
end
print("Collected", gained, "raw iron; return to the workstation to smelt and equip this batch.")
```

| Tier | Unlocks mining | Recipe |
|---|---|---|
| Hand | logs, dirt, gravel | — |
| Wooden pickaxe | stone, coal | 3 planks + 2 sticks |
| Stone pickaxe | iron, lapis | 3 cobblestone + 2 sticks |
| Iron pickaxe | diamond, gold, redstone | 3 iron ingots + 2 sticks |
| Diamond pickaxe | obsidian | 3 diamonds + 2 sticks |

## Where ores live (1.21+ worldgen)

Below Y 0 every ore is its **deepslate variant** — always pass both ids to `numen.scan.blocks` (e.g. `diamond_ore` *and* `deepslate_diamond_ore`).

| Resource | Target Y | Notes |
|---|---|---|
| Coal | Y 90–136 | Surface hillsides are fastest |
| Iron | Y 16 (or mountain surface Y 200+) | Drops `raw_iron`, smelt it |
| **Diamond** | **Y -58 to -59** | Highest density; lava pools at this depth — mine carefully |

## Recommended order

1. **Wood**: `numen.scan.blocks("oak_log", "birch_log", "spruce_log")`, then `numen.work.mine` the nearest tree — again until you hold 8+ logs (any `*_log`; hand works) → craft planks → sticks → a `wooden_pickaxe`. Crafting = `numen.inv.make` (it picks the recipe and lays the grid for you). 2×2 recipes (planks, sticks) use your own grid; a 3×3 (the pickaxe) needs a crafting table — `numen.inv.make` walks to one within 16 blocks, or puts down the one you carry: once you have planks, `numen.inv.make("crafting_table")` first. Remember the table's coordinates and reuse it.
2. **Stone**: `numen.gear.hold("wooden_pickaxe")` → `numen.scan.blocks("stone", {radius = 4})` → `numen.work.dig` the nearest cluster's blocks where you stand (`count` caps it), `numen.work.collect` — 20 cobblestone → craft a `stone_pickaxe`, `stone_sword`, and a `furnace`.
3. **Food**: scan cows/pigs/chickens and `numen.fight.attack` each runtime id (6+ total), `numen.work.collect()` after → cook the raw meat: `numen.use.block` a furnace, `numen.gui.quick` the raw food (it goes to the top slot) and the fuel (coal or planks, it goes below), set a `numen.task.timer`, then `numen.gui.quick` the cooked food out (see the `containers` skill). Always cook; raw meat barely heals.
4. **Iron**: `numen.gear.hold("stone_pickaxe")` before descending (`numen.move.to({y = 16}, {costs = {dig = true, place = true}})`) → scan both iron variants → mine a small batch with `numen.work.mine(vein, {count = 4})` → return to a safe workstation, smelt and take the ingots → craft an `iron_pickaxe` (3 ingots) and a `shield` (1 ingot + planks), and hold the shield. Gather the next batch for one armor piece and wear it immediately (chestplate 8, leggings 7, helmet 5, boots 4); repeat until the requested kit is complete. A sword or bucket costs another 2 or 3 ingots when needed. Recover health and food between trips.
5. **Diamonds**: `numen.move.to({y = -58}, {costs = {dig = true, place = true}})` → `numen.gear.hold("iron_pickaxe")` → `numen.scan.blocks("deepslate_diamond_ore", "diamond_ore")` → `numen.work.mine` the nearest cluster, for 5+. Minimum 5 (pickaxe 3 + sword 2); 8+ if you also want a chestplate later. Watch HP near lava.
6. **Diamond gear**: craft a `diamond_pickaxe` + `diamond_sword` on the crafting table. Keep the pickaxe in hand for travel and mining; equip the sword only when a fight starts.
7. **Bow + arrows**: bow = 3 sticks + 3 string (scan spiders at night and `numen.fight.attack` each for string); arrows = 1 flint + 1 stick + 1 feather → 4 (flint drops from gravel you `numen.work.dig` at ~10%, feathers from chickens). Target 32 arrows — more is comfort, not requirement; melee + food covers what arrows don't.
8. **Top up**: 32+ cooked food, 64+ cobblestone. Check the current inventory and `numen.status.self()` against the "done when" list.

## Enchanting

You cannot operate an enchanting table (GUI block). If your owner offers to enchant your gear — Sharpness on the sword, Power on the bow, Efficiency on the pickaxe — accept before moving on; it meaningfully raises dragon-fight odds. Never plan an enchanting step for yourself.

## What to load next

For an iron preparation goal, report the carried supplies and worn equipment and stop when that goal is met. For the dragon route, verify the full checklist → mark phase 1 `[x]` with the `todo` tool, then load the `nether_entry` skill.
