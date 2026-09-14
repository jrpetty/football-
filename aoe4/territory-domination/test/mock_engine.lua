-----------------------------------------------------------------------------
-- mock_engine.lua
--
-- A stand-in for the AoE4 Scar engine, good enough to run the Territory
-- Domination logic outside the game.
--
-- This does NOT prove the real engine function names are correct -- that is
-- exactly what cannot be checked without the game. What it does prove is
-- that the mode's own logic is sound.
--
-- The mock deliberately does NOT provide a cumulative "resources gathered"
-- stat, so the tests exercise the harder stock-delta fallback path in
-- td_income.scar. Mock.EnableCumulativeStats() switches it on to test the
-- other branch.
-----------------------------------------------------------------------------

Mock = {
	players = {},
	squads = {},
	stock = {},        -- [player][key] = current resource stock
	gathered = {},     -- [player][key] = lifetime gathered (cumulative path)
	messages = {},
	rules = {},
	visuals = {},      -- created decals/blips, for visual assertions
	time = 0,
	winner = nil,
	defeated = {},
	mapSize = 400,
	cumulativeStats = false,
	starts = {},       -- [player] = {x=, z=}; unset means "engine cannot say"
}

local KEYS = { "food", "wood", "gold", "stone" }
local KEY_BY_RT = { [0] = "food", [1] = "wood", [2] = "gold", [3] = "stone" }

function Mock.Reset(playerCount, mapSize)
	Mock.players, Mock.squads, Mock.stock, Mock.gathered = {}, {}, {}, {}
	Mock.messages, Mock.rules, Mock.visuals = {}, {}, {}
	Mock.time, Mock.winner, Mock.defeated = 0, nil, {}
	Mock.mapSize = mapSize or 400
	Mock.cumulativeStats = false
	Mock.starts = {}
	for i = 1, playerCount do
		Mock.players[i] = i
		Mock.squads[i] = {}
		Mock.stock[i] = { food = 0, wood = 0, gold = 0, stone = 0 }
		Mock.gathered[i] = { food = 0, wood = 0, gold = 0, stone = 0 }
	end
end

function Mock.EnableCumulativeStats()
	Mock.cumulativeStats = true
end

function Mock.SetStart(player, x, z)
	Mock.starts[player] = { x = x, y = 0, z = z }
end

-- Place players evenly around a circle, the way a symmetric skirmish map
-- would. Radius is a fraction of the map half-width.
function Mock.SetSymmetricStarts(radiusFraction)
	local radius = Mock.mapSize * 0.5 * (radiusFraction or 0.7)
	local n = #Mock.players
	for i, player in ipairs(Mock.players) do
		local angle = (i - 1) / n * math.pi * 2
		Mock.SetStart(player, math.cos(angle) * radius, math.sin(angle) * radius)
	end
end

function Mock.PlaceSquads(player, x, z, count)
	table.insert(Mock.squads[player], { x = x, z = z, count = count })
end

function Mock.ClearSquads(player)
	Mock.squads[player] = {}
end

-- Simulate a player mining resources: raises both stock and lifetime total.
function Mock.Gather(player, amount)
	for _, key in ipairs(KEYS) do
		Mock.stock[player][key] = Mock.stock[player][key] + amount
		Mock.gathered[player][key] = Mock.gathered[player][key] + amount
	end
end

-- Simulate a player spending: lowers stock only, never lifetime total.
function Mock.Spend(player, amount)
	for _, key in ipairs(KEYS) do
		Mock.stock[player][key] = math.max(0, Mock.stock[player][key] - amount)
	end
end

function Mock.Kill(player)
	Mock.defeated[player] = true
end

function Mock.Advance(seconds, step)
	step = step or 0.5
	local target = Mock.time + seconds
	while Mock.time < target do
		Mock.time = Mock.time + step
		for _, rule in ipairs(Mock.rules) do
			if not rule.removed and Mock.time >= rule.nextRun then
				rule.nextRun = Mock.time + rule.interval
				rule.fn()
			end
		end
	end
end

-- Advance time while a player gathers at a steady per-second rate, which is
-- what the income sampler is designed to measure.
function Mock.AdvanceGathering(seconds, ratePerSecond, players, step)
	step = step or 0.5
	local target = Mock.time + seconds
	while Mock.time < target do
		Mock.time = Mock.time + step
		for _, p in ipairs(players) do
			if not Mock.defeated[p] then
				Mock.Gather(p, ratePerSecond * step)
			end
		end
		for _, rule in ipairs(Mock.rules) do
			if not rule.removed and Mock.time >= rule.nextRun then
				rule.nextRun = Mock.time + rule.interval
				rule.fn()
			end
		end
	end
end

-----------------------------------------------------------------------------
-- ENGINE STUBS
-----------------------------------------------------------------------------

function World_GetPlayerCount() return #Mock.players end
function World_GetPlayerAt(i) return Mock.players[i + 1] end
function Player_IsAlive(p) return not Mock.defeated[p] end
function Player_GetDisplayName(p) return "P" .. tostring(p) end
function Player_GetUIColour(p)
	local palette = {
		{ r = 60, g = 110, b = 220 }, { r = 220, g = 70, b = 60 },
		{ r = 80, g = 180, b = 90 },  { r = 230, g = 190, b = 70 },
	}
	return palette[((p - 1) % #palette) + 1]
end

-- Deliberately absent so the ledger path is exercised; see GetConqueror.
-- function Player_GetLastAttacker(p) end

-- Errors when a test has not set starting positions, so the positionless
-- fallback in AssignStartingZones gets exercised too.
function Player_GetStartingPosition(player)
	local pos = Mock.starts[player]
	if pos == nil then
		error("no starting position set for player " .. tostring(player))
	end
	return pos
end

function World_GetWidth() return Mock.mapSize end
function World_GetHeight() return Mock.mapSize end
function World_Pos(x, y, z) return { x = x, y = y or 0, z = z } end
function World_GetSpawnablePosition(pos) return pos end
function World_IsPointOverImpassableTerrain(pos)
	-- Lake in one corner, placed inside the outermost ring of zone centres
	-- so it actually removes cells and the skip logic is tested.
	local edge = Mock.mapSize * 0.15
	return pos.x > edge and pos.z > edge
end

function Player_GetSquadsNearPoint(player, pos, radius)
	local total = 0
	for _, group in ipairs(Mock.squads[player] or {}) do
		local dx, dz = group.x - pos.x, group.z - pos.z
		if math.sqrt(dx * dx + dz * dz) <= radius then
			total = total + group.count
		end
	end
	return { _count = total }
end
function SGroup_CountSpawned(sgroup) return sgroup._count or 0 end

RT_Food, RT_Wood, RT_Gold, RT_Stone = 0, 1, 2, 3

function Player_AddResource(player, rtype, amount)
	local key = KEY_BY_RT[rtype]
	Mock.stock[player][key] = Mock.stock[player][key] + amount
end
function Player_GetResource(player, rtype)
	return Mock.stock[player][KEY_BY_RT[rtype]]
end

-- Only defined when the test opts in, so the default path is the fallback.
function Player_GetResourceGathered(player, rtype)
	if not Mock.cumulativeStats then
		error("cumulative stats disabled in this test")
	end
	return Mock.gathered[player][KEY_BY_RT[rtype]]
end

function UI_SystemMessageShow(text) table.insert(Mock.messages, text) end

local function _makeVisual(kind, pos, arg, colour, opacity)
	local v = { kind = kind, pos = pos, arg = arg, colour = colour,
	            opacity = opacity, destroyed = false }
	table.insert(Mock.visuals, v)
	return v
end
function UI_CreateGroundDecal(pos, radius, thickness, colour, opacity)
	return _makeVisual("decal", pos, radius, colour, opacity)
end
function UI_CreateMinimapBlip(pos, scale, colour)
	return _makeVisual("blip", pos, scale, colour, 1.0)
end
function UI_SetDecalColour(handle, colour, opacity)
	handle.colour, handle.opacity = colour, opacity
end
function UI_DestroyDecal(handle) handle.destroyed = true end

function World_SetPlayerWin(player) Mock.winner = player end
function World_SetPlayerLose(player) Mock.defeated[player] = true end

function Rule_AddInterval(fn, interval)
	table.insert(Mock.rules, { fn = fn, interval = interval, nextRun = Mock.time + interval })
end
function Rule_RemoveGlobalEvent(fn)
	for _, rule in ipairs(Mock.rules) do
		if rule.fn == fn then rule.removed = true end
	end
end

function import(file)
	assert(loadfile("scar/" .. file))()
end

return Mock
