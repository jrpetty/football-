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
TD_API.verbose = false
TD_Config.visuals.enabled = false

TD_Zones.Build()

local n = #TD_Zones.list
local lo, hi = math.huge, -math.huge
for _, z in ipairs(TD_Zones.list) do
	lo, hi = math.min(lo, z.weight), math.max(hi, z.weight)
end

print(string.format("\nZONES: %d   weight range %.2f - %.2f   (mean 1.00 by construction)",
	n, lo, hi))
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

print(string.format("\nCAPTURE TIMES (uncontested):"))
for _, squads in ipairs({ 1, 2, 4, 8, 20 }) do
	local rate = math.min(squads * TD_Config.captureRatePerSquad, TD_Config.captureRateCap)
	print(string.format("  %2d squads -> %5.1f s%s", squads,
		TD_Config.captureThreshold / rate * TD_Config.scanInterval,
		(squads * TD_Config.captureRatePerSquad > TD_Config.captureRateCap) and "  (rate capped)" or ""))
end

print(string.format("\nMATCH END: last player standing. No score cap, no timer."))
print("CONQUEST: killing a player transfers all of their remaining zones to you.\n")
