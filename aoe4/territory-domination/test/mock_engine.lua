-----------------------------------------------------------------------------
-- mock_engine.lua
--
-- A stand-in for the AoE4 Scar engine, good enough to run the Territory
-- Domination logic outside the game.
--
-- This does NOT prove the real engine function names are correct -- that is
-- exactly what cannot be checked without the game. What it does prove is
-- that the mode's own logic is sound: that zones lay out sensibly, that
-- capture progresses and flips when it should, that contested zones stop
-- paying, that income scales with zone value, and that the match ends with
-- the right winner. Those are the parts that would otherwise be debugged
-- by trial and error in-game.
-----------------------------------------------------------------------------

Mock = {
	players = {},
	squads = {},        -- [player] = { {pos = {x,z}, count = n}, ... }
	resources = {},     -- [player] = { [type] = amount }
	messages = {},
	rules = {},         -- { {fn = f, interval = n, nextRun = t} }
	time = 0,
	winner = nil,
	defeated = {},
	mapSize = 400,
}

function Mock.Reset(playerCount, mapSize)
	Mock.players = {}
	Mock.squads = {}
	Mock.resources = {}
	Mock.messages = {}
	Mock.rules = {}
	Mock.time = 0
	Mock.winner = nil
	Mock.defeated = {}
	Mock.mapSize = mapSize or 400
	for i = 1, playerCount do
		Mock.players[i] = i
		Mock.squads[i] = {}
		Mock.resources[i] = { [0] = 0, [1] = 0, [2] = 0, [3] = 0 }
	end
end

-- Place `count` squads for a player at a world position.
function Mock.PlaceSquads(player, x, z, count)
	table.insert(Mock.squads[player], { x = x, z = z, count = count })
end

function Mock.ClearSquads(player)
	Mock.squads[player] = {}
end

-- Run the rule scheduler forward by `seconds`, stepping at `step` resolution.
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

-----------------------------------------------------------------------------
-- ENGINE STUBS
-- Named to match what td_adapter.scar looks for.
-----------------------------------------------------------------------------

function World_GetPlayerCount() return #Mock.players end
function World_GetPlayerAt(i) return Mock.players[i + 1] end
function Player_IsAlive(p) return not Mock.defeated[p] end
function Player_GetDisplayName(p) return "P" .. tostring(p) end

function World_GetWidth() return Mock.mapSize end
function World_GetHeight() return Mock.mapSize end
function World_Pos(x, y, z) return { x = x, y = y or 0, z = z } end
function World_GetSpawnablePosition(pos) return pos end
function World_IsPointOverImpassableTerrain(pos)
	-- Carve a lake into one corner so the unplayable-cell skip is exercised.
	-- The threshold is deliberately inside the outermost ring of zone centres:
	-- a lake beyond them would be skipped by nothing and test nothing.
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
	Mock.resources[player][rtype] = (Mock.resources[player][rtype] or 0) + amount
end

function UI_SystemMessageShow(text) table.insert(Mock.messages, text) end
function UI_CreateMinimapBlip(pos, label) return { pos = pos, label = label } end
function UI_SetMinimapBlipOwner(marker, player) marker.owner = player end

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

-- Scar's import(); resolves relative to the scar/ directory.
function import(file)
	local path = "scar/" .. file:gsub("%.scar$", ".scar")
	local chunk = assert(loadfile(path))
	chunk()
end

return Mock
