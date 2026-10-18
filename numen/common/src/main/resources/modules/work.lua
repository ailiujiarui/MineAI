-- Picking up and digging out: walk onto the dropped items lying around, dig out a cluster.
local M = {}

---Pick up the dropped items around you, nearest first, walking up to each with numen.move.to(item.id): to where the pickup
---reaches the item itself (an entity), not into the cell its coordinates round to (an item resting on a ledge's edge rounds
---to the empty cell beside it). Given items (the drops
---numen.work.dig returned), it goes only after those of them still lying around. A fresh drop cannot be picked up for
---a few ticks (its pickup_delay): standing by it, it walks up to it again until it is taken. An item with no way to it
---(numen.move.to fails with no_path) is passed over and the rest are picked up; at the end the ones passed over raise
---no_path, with them in err.data.left. An item still there after walking onto it with no delay left (a full pack, or
---a spot you cannot stand in) raises failed; any other error of a walk stops here as it is.
---@param opts? table items = only these (anything with an id: the drops of numen.work.dig, Items of numen.scan.entities); radius = how far to look (default 8, or 64 with items).
---@return integer picked How many items it walked onto that are gone now.
function M.collect(opts)
  opts = opts or {}
  local wanted = nil
  if opts.items then
    wanted = {}
    for _, item in ipairs(opts.items) do
      if item.id then
        wanted[item.id] = true
      end
    end
  end
  local radius = opts.radius or (wanted and 64 or 8)
  local walked = {}
  -- the pickup happens on the tick after the delay reaches 0: seen at 0 once is still on its way, seen at 0 twice stays
  local due = {}
  local unreachable = {}
  while true do
    local items = {}
    for _, item in ipairs(numen.scan.entities("item", {radius = radius})) do
      if not unreachable[item.id] and (wanted == nil or wanted[item.id]) then
        items[#items + 1] = item
      end
    end
    if #items == 0 then
      local picked = 0
      for _, n in pairs(walked) do
        picked = picked + n
      end
      local left = {}
      for _, item in pairs(unreachable) do
        left[#left + 1] = item
      end
      if #left > 0 then
        raise("no_path", "picked up " .. picked .. " item(s); no way to the " .. #left .. " left, the nearest "
            .. left[1].item .. " x" .. left[1].count, string.format("numen.move.to(%d)", left[1].id),
            {picked = picked, left = left})
      end
      return picked
    end
    local item = items[1]
    if walked[item.id] and item.pickup_delay == 0 then
      if due[item.id] then
        raise("failed", item.item .. " x" .. item.count .. " is still there after walking onto it: a full pack, or a "
            .. "spot you cannot stand in", nil, {item = item})
      end
      due[item.id] = true
    end
    if item.pickup_delay > 100 then
      raise("failed", item.item .. " x" .. item.count .. " cannot be picked up for another " .. item.pickup_delay
          .. " ticks", nil, {item = item})
    end
    local ok, err = pcall(numen.move.to, item.id)
    if ok then
      walked[item.id] = item.count
    elseif err.kind == "no_path" then
      unreachable[item.id] = item
    elseif err.kind == "not_found" then
      -- taken (by her, on the way) or gone while she walked: the next look at the ground decides what is left
    else
      error(err, 0)
    end
  end
end

---Dig out one cluster: numen.work.dig walks up to what is out of reach and digs it (standing where the hand reaches the
---most of what is left), then pick up what that dug dropped and still lies around (numen.work.collect with its drops),
---and again until none of it is left or opts.count cells have been dug and collected. Use count for a small preparation
---batch; return to smelt and use it before starting the next batch. Before and after each dig and each pick-up, stop
---with failed (data.reason = "unsafe", dug and any drops) if health falls, is at most half, breath is below half, you
---are in lava, or a hostile within 16 blocks is visible. Reassess that result instead of retrying the same failed
---batch. A round that digs nothing raises failed with what is left; any other failing step (a dig that can't get there
---or is refused, a pick-up with no way) raises its error as it is.
---@param cluster Cluster|Cells What to dig: a cluster numen.scan.blocks found, as it is (or Cells, or a list of Blocks).
---@param opts? {count?: integer} Maximum cells to dig and collect this call; omitted = the whole cluster.
---@return integer dug How many cells it dug.
function M.mine(cluster, opts)
  if opts ~= nil and type(opts) ~= "table" then
    raise("bad_argument", "the second argument is not a table of options", nil, {got = opts})
  end
  -- Lua drops arguments a function does not name: an option other than count would vanish here and the caller would
  -- think it had taken effect. Refuse it instead of swallowing it.
  for key in pairs(opts or {}) do
    if key ~= "count" then
      raise("bad_argument", "there is no option " .. tostring(key)
          .. "; numen.work.mine takes only what to dig and count (how many cells to dig and collect)", nil, {got = opts})
    end
  end
  local limit = (opts or {}).count
  if limit ~= nil and (type(limit) ~= "number" or limit < 1 or limit % 1 ~= 0) then
    raise("bad_argument", "count must be a positive integer")
  end
  local dug = 0
  local previous_hp = nil
  local function checkpoint(progress)
    local me = numen.status.self()
    local reason = nil
    local threat = nil
    if me.hp <= me.max_hp / 2 then
      reason = "health is at most half"
    elseif previous_hp and me.hp < previous_hp then
      reason = "health fell during the batch"
    elseif me.in_lava then
      reason = "in lava"
    elseif me.air < me.max_air / 2 then
      reason = "breath is below half"
    end
    if reason == nil then
      for _, foe in ipairs(numen.scan.entities("hostile", {radius = 16})) do
        if numen.scan.sight(foe.id).visible then
          reason = "a hostile is visible nearby"
          threat = foe
          break
        end
      end
    end
    if reason then
      raise("failed", "stopped mining after digging " .. dug .. " cell(s): " .. reason,
          "reassess safety, health and the drops before starting another batch",
          {reason = "unsafe", dug = dug, hp = me.hp, air = me.air, threat = threat,
              drops = progress and progress.drops, left = progress and progress.left})
    end
    previous_hp = me.hp
  end
  while true do
    checkpoint()
    local r = limit and numen.work.dig(cluster, {count = limit - dug}) or numen.work.dig(cluster)
    dug = dug + r.dug
    if r.dug == 0 and r.left > 0 then
      raise("failed", "dug nothing this round and " .. r.left .. " cell(s) of it are left", nil,
          {reason = "no_progress", dug = dug, left = r.left, nearest = r.nearest, drops = r.drops})
    end
    checkpoint(r)
    M.collect({items = r.drops})
    checkpoint(r)
    if r.left == 0 or limit and dug >= limit then
      return dug
    end
  end
end

return M
