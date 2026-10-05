-- Higher-level AE2 jobs built from the ae2.* atomics: submit one autocraft and follow it to the end, encode a pattern and make sure it came out, wait for a network to come online.
local M = {}

---Submit one autocraft with ae2.craft.request and follow it with ae2.craft.status until it stops, without ever sending
---a second request. Returns the last status record when AE2 reports the job completed (outputs returned to ME storage,
---not your inventory); raises the status state with its detail when the job failed or was canceled.
---@param x number Block X of any AE2 device on the network (stand within 8 blocks of it).
---@param y number Block Y.
---@param z number Block Z.
---@param item string Namespaced item id to craft, e.g. minecraft:oak_planks.
---@param count? number How many EXTRA items to craft, not a stock target (default 1).
---@param opts? {every?: number, timeout?: number} Poll every opts.every seconds (default 2); raise timeout after opts.timeout seconds (default 300).
---@return table status The ae2.craft.status record, whose state is "completed".
function M.request_until_done(x, y, z, item, count, opts)
  opts = opts or {}
  local request = ae2.craft.request(x, y, z, item, count or 1)
  local last = nil
  numen.time.wait_until(function()
    last = ae2.craft.status(request.requestId)
    return last.state ~= "calculating" and last.state ~= "submitted"
  end, {every = opts.every or 2, timeout = opts.timeout or 300})
  if last.state ~= "completed" then
    raise(last.state, "autocraft " .. request.requestId .. " ended " .. last.state .. ": " .. last.detail, nil)
  end
  return last
end

---Encode a pattern in the currently open ME Pattern Encoding terminal (ae2.pattern.encode) and make sure the finished
---pattern landed in your inventory. spec is exactly what ae2.pattern.encode takes:
---{inputs = {{item = "minecraft:oak_log"}}, outputs = {{item = "minecraft:oak_planks", count = 4}},
---mode = "processing" or "crafting", substitute = true/false}. Returns ae2.pattern.encode's record; raises failed with
---where the pattern went when AE2 left it in the terminal's output slot instead.
---@param spec table What ae2.pattern.encode takes.
---@return table encoded The ae2.pattern.encode record.
function M.ensure_pattern_encoded(spec)
  local encoded = ae2.pattern.encode(spec)
  if not encoded.inInventory then
    raise("failed", "the pattern is not in my inventory (" .. encoded.where .. ")",
        "take it out of the terminal's output slot (numen.gui.quick), then call again")
  end
  return encoded
end

---Wait until the AE2 network at (x, y, z) is on a grid, its node is active and the grid is powered, reading
---ae2.network.inspect every opts.every seconds. Returns the last ae2.network.inspect record; numen.time.wait_until
---raises timeout if it never comes online.
---@param x number Block X of a cable or device on the network.
---@param y number Block Y.
---@param z number Block Z.
---@param opts? {every?: number, timeout?: number} Poll every opts.every seconds (default 2); give up after opts.timeout seconds (default 60).
---@return table network The ae2.network.inspect record once it is online.
function M.wait_online(x, y, z, opts)
  opts = opts or {}
  local last = nil
  numen.time.wait_until(function()
    last = ae2.network.inspect(x, y, z)
    return last.onGrid and last.nodeActive and last.gridPowered
  end, {every = opts.every or 2, timeout = opts.timeout or 60})
  return last
end

return M
