-- balance_sim.lua -- reports what the current config actually does, so the
-- tuning numbers can be sanity-checked without playing a game.
-- Run from the mod root:  lua5.4 test/balance_sim.lua
require("test.mock_engine")

Mock.Reset(4)
dofile("scar/td_config.scar")
dofile("scar/td_adapter.scar")
dofile("scar/td_visuals.scar")
dofile("scar/td_zones.scar")
dofile("scar/td_income.scar")
dofile("scar/td_king.scar")
TD_API.verbose = false
TD_Config.visuals.enabled = false

TD_Zones.Build()

local n = #TD_Zones.list
local lo, hi, water = math.huge, -math.huge, 0
for _, z in ipairs(TD_Zones.list) do
	lo, hi = math.min(lo, z.weight), math.max(hi, z.weight)
	if z.isWater then water = water + 1 end
end

print(string.format("\nZONES: %d (%d land, %d water)   weight range %.2f - %.2f"
	.. "   (mean 1.00 by construction)", n, n - water, water, lo, hi))
print(string.format("  an edge zone is worth   %.1f%% boost", lo * TD_Config.boostPerZone * 100))
print(string.format("  a centre zone is worth  %.1f%% boost", hi * TD_Config.boostPerZone * 100))

print("\nBOOST BY ZONES HELD (average-value zones):")
for _, held in ipairs({ 1, 2, 3, 5, 8, 12, n }) do
	if held <= n then
		local raw = held * TD_Config.boostPerZone
		local capped = TD_Config.maxTotalBoost and math.min(raw, TD_Config.maxTotalBoost) or raw
		print(string.format("  %2d zones -> +%3.0f%% gather rate%s",
			held, capped * 100,
			(capped < raw) and string.format("  (capped from +%.0f%%)", raw * 100) or ""))
	end
end

print("\nWHAT THE BOOST IS WORTH, given a gather rate per minute:")
print("  (boost is a percentage of what you actually mine, so this scales)")
local header = "  zones "
for _, rate in ipairs({ 500, 1000, 2000, 3000 }) do
	header = header .. string.format("%9d/min", rate)
end
print(header)
for _, held in ipairs({ 1, 3, 5, 8 }) do
	if held <= n then
		local boost = TD_Config.maxTotalBoost
			and math.min(held * TD_Config.boostPerZone, TD_Config.maxTotalBoost)
			or held * TD_Config.boostPerZone
		local row = string.format("  %5d ", held)
		for _, rate in ipairs({ 500, 1000, 2000, 3000 }) do
			row = row .. string.format("%9.0f  ", rate * boost)
		end
		print(row)
	end
end

print("\nCAPTURE TIMES (uncontested), land and water alike:")
for _, squads in ipairs({ 1, 2, 3, 4, 8, 20 }) do
	local rate = math.min(squads * TD_Config.captureRatePerSquad, TD_Config.captureRateCap)
	print(string.format("  %2d unit%s -> %5.1f s%s", squads, squads == 1 and " " or "s",
		TD_Config.captureThreshold / rate * TD_Config.scanInterval,
		(squads * TD_Config.captureRatePerSquad > TD_Config.captureRateCap) and "  (rate capped)" or ""))
end

-- The opening layout, which is what cell size is derived from.
print("\nOPENING LAYOUT  (base -> home ring -> neutral -> their ring -> their base)")
print("  players    grid    tiles     cell   base sep   neutral band   per head")
for _, case in ipairs({ { 2, 400 }, { 3, 480 }, { 4, 560 }, { 6, 690 }, { 8, 800 } }) do
	local players, size = case[1], case[2]
	Mock.Reset(players, size)
	dofile("scar/td_config.scar"); dofile("scar/td_adapter.scar")
	dofile("scar/td_visuals.scar"); dofile("scar/td_zones.scar")
	dofile("scar/td_income.scar"); dofile("scar/td_king.scar")
	dofile("scar/td_tally.scar"); dofile("scar/territorydomination.scar")
	TD_API.verbose = false
	TD_Config.visuals.enabled = false
	Mock.SetSymmetricStarts(0.7)
	TerritoryDomination_OnInit()

	local bases = {}
	for _, zone in ipairs(TD_Zones.list) do
		if zone.owner ~= nil then bases[zone.owner] = zone end
	end
	local closest = math.huge
	for a = 1, players do
		for b = a + 1, players do
			if bases[a] and bases[b] then
				closest = math.min(closest, math.max(
					math.abs(bases[a].col - bases[b].col),
					math.abs(bases[a].row - bases[b].row)))
			end
		end
	end
	print(string.format("  %7d  %3dx%-3d  %5d  %7.1f  %9d  %13d  %9.1f",
		players, TD_Zones.cols, TD_Zones.rows, #TD_Zones.list,
		TD_Zones.list[1].halfW * 2, closest,
		closest - 2 * TD_Config.homeRingRadius - 1, #TD_Zones.list / players))
end
print(string.format("  (bases must sit %d tiles apart: 2 x ring %d + gap %d + 1)",
	TD_Zones.BasesApartTiles(), TD_Config.homeRingRadius, TD_Config.neutralGapTiles))

-- What that layout does to the economy.
print("\nECONOMY AT " .. math.floor(TD_Config.boostPerZone * 100) .. "% PER TILE:")
print("  players    tiles    home ring   fair share   whole map")
for _, case in ipairs({ { 2, 400 }, { 4, 560 }, { 8, 800 } }) do
	local players, size = case[1], case[2]
	Mock.Reset(players, size)
	dofile("scar/td_config.scar"); dofile("scar/td_adapter.scar")
	dofile("scar/td_visuals.scar"); dofile("scar/td_zones.scar")
	dofile("scar/td_income.scar"); dofile("scar/td_king.scar")
	dofile("scar/td_tally.scar"); dofile("scar/territorydomination.scar")
	TD_API.verbose = false
	TD_Config.visuals.enabled = false
	Mock.SetSymmetricStarts(0.7)
	TerritoryDomination_OnInit()

	local per, tiles = TD_Income.BoostPerZone(), #TD_Zones.list
	print(string.format("  %7d  %7d  %10.0f%%  %10.0f%%  %9.0f%%",
		players, tiles, 9 * per * 100, tiles / players * per * 100, tiles * per * 100))
end
print("  The home ring is always 9 tiles, so it is always the same boost.")
print("  Everything past it is not: set boostAutoScale to hold the map total")
print("  constant instead of the per-tile figure.")

-- Zone count scaling across the map sizes a skirmish supports.
print("\nZONE COUNT SCALING (bigger maps and more players get more zones):")
print("  players   map size   zones   per player   water")
for _, case in ipairs({ { 2, 400 }, { 3, 480 }, { 4, 560 }, { 6, 690 }, { 8, 800 } }) do
	local players, size = case[1], case[2]
	Mock.Reset(players, size)
	dofile("scar/td_config.scar"); dofile("scar/td_adapter.scar")
	dofile("scar/td_visuals.scar"); dofile("scar/td_zones.scar")
	dofile("scar/td_income.scar"); dofile("scar/td_king.scar")
	dofile("scar/territorydomination.scar")
	TD_API.verbose = false
	TD_Config.visuals.enabled = false
	TD_Zones.Build(players)

	local waterCount = 0
	for _, zone in ipairs(TD_Zones.list) do
		if zone.isWater then waterCount = waterCount + 1 end
	end
	print(string.format("  %7d   %8.0f   %5d   %10.1f   %5d",
		players, size, #TD_Zones.list, #TD_Zones.list / players, waterCount))
end

-- Starting zone assignment, across the player counts a skirmish supports.
print("\nSTARTING ZONES (every player begins owning one):")
for _, count in ipairs({ 2, 4, 8 }) do
	Mock.Reset(count)
	dofile("scar/td_config.scar"); dofile("scar/td_adapter.scar")
	dofile("scar/td_visuals.scar"); dofile("scar/td_zones.scar")
	dofile("scar/td_income.scar"); dofile("scar/td_king.scar")
	dofile("scar/territorydomination.scar")
	TD_API.verbose = false
	TD_Config.visuals.enabled = false
	Mock.SetSymmetricStarts(0.7)
	TerritoryDomination_OnInit()

	local lo, hi, maxDist, everyone = math.huge, -math.huge, 0, true
	for player = 1, count do
		if TD_Zones.CountOwned(player) ~= 1 then everyone = false end
	end
	for _, zone in ipairs(TD_Zones.list) do
		if zone.owner ~= nil then
			lo, hi = math.min(lo, zone.weight), math.max(hi, zone.weight)
			local st = Mock.starts[zone.owner]
			maxDist = math.max(maxDist, math.sqrt(
				(zone.position.x - st.x) ^ 2 + (zone.position.z - st.z) ^ 2))
		end
	end
	print(string.format(
		"  %d players: starting boost %.1f%% - %.1f%%  (spread %2.0f%%), "
			.. "furthest start %3.0f units%s",
		count, lo * TD_Config.boostPerZone * 100, hi * TD_Config.boostPerZone * 100,
		(hi / lo - 1) * 100, maxDist,
		everyone and "" or "   <-- NOT EVERYONE GOT A ZONE"))
end

print("\nMATCH END: last player standing. No score cap, no timer.")
print(string.format("KINGS: %s%s",
	TD_Config.kings.enabled and "one per player at their starting position"
		or "disabled",
	TD_Config.kings.kingDeathEliminates and "; losing yours eliminates you" or ""))
print("CONQUEST: killing a player transfers all of their remaining zones to you.\n")
