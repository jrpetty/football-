-- balance_sim.lua -- estimates match length and income rates for the current
-- config, so the tuning numbers can be sanity-checked without playing a game.
-- Run: lua5.4 test/balance_sim.lua
require("test.mock_engine")

Mock.Reset(4)
dofile("scar/td_config.scar")
dofile("scar/td_adapter.scar")
dofile("scar/td_zones.scar")
dofile("scar/td_score.scar")
TD_API.verbose = false

TD_Zones.Build()

local total, byValue = 0, {}
for _, z in ipairs(TD_Zones.list) do
	total = total + z.value
	byValue[z.value] = (byValue[z.value] or 0) + 1
end

print(string.format("Zones: %d   total map value: %d", #TD_Zones.list, total))
local tiers = {}
for v, n in pairs(byValue) do table.insert(tiers, { v = v, n = n }) end
table.sort(tiers, function(a, b) return a.v > b.v end)
for _, t in ipairs(tiers) do
	print(string.format("  value %d : %2d zones", t.v, t.n))
end

print("\nTime to reach score cap of " .. TD_Config.scoreCap .. ":")
local perTick = TD_Config.incomeInterval
for _, share in ipairs({ 0.15, 0.25, 0.40, 0.60, 0.80 }) do
	local held = total * share
	local scorePerTick = held * TD_Config.scorePerZoneValue
	local secs = TD_Config.scoreCap / scorePerTick * perTick
	print(string.format(
		"  holding %3d%% of map value (%5.1f) -> %5.1f score/tick -> %4.1f min",
		share * 100, held, scorePerTick, secs / 60
	))
end

print("\nResource income per minute at each map share:")
local r = TD_Config.incomePerValue
local ticksPerMin = 60 / TD_Config.incomeInterval
for _, share in ipairs({ 0.15, 0.25, 0.40, 0.60 }) do
	local held = total * share
	print(string.format(
		"  %3d%% -> food %5.0f  wood %5.0f  gold %5.0f  stone %5.0f  per min",
		share * 100,
		r.food * held * ticksPerMin, r.wood * held * ticksPerMin,
		r.gold * held * ticksPerMin, r.stone * held * ticksPerMin
	))
end

print(string.format(
	"\nCapture time for one zone (uncontested, %d squads): %.1f s",
	4, TD_Config.captureThreshold
		/ math.min(4 * TD_Config.captureRatePerSquad, TD_Config.captureRateCap)
		* TD_Config.scanInterval))
