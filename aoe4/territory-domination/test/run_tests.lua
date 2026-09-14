-----------------------------------------------------------------------------
-- run_tests.lua -- logic tests for Territory Domination.
-- Run from the mod root:  lua5.4 test/run_tests.lua
-----------------------------------------------------------------------------

require("test.mock_engine")

local passed, failed = 0, 0

local function check(name, condition, detail)
	if condition then
		passed = passed + 1
		print(string.format("  PASS  %s", name))
	else
		failed = failed + 1
		print(string.format("  FAIL  %s%s", name, detail and ("  -- " .. detail) or ""))
	end
end

local function section(name) print("\n" .. name) end

local function loadMod()
	dofile("scar/td_config.scar")
	dofile("scar/td_adapter.scar")
	dofile("scar/td_visuals.scar")
	dofile("scar/td_zones.scar")
	dofile("scar/td_income.scar")
	dofile("scar/territorydomination.scar")
	TD_API.verbose = false
	-- Off by default so the mechanic sections below start from a clean,
	-- fully neutral map. The starting-zone section switches it back on.
	TD_Config.startingZonePerPlayer = false
end

-- Give a player uncontested ownership of a zone, the slow honest way.
local function captureFor(player, zone, players, squads)
	Mock.PlaceSquads(player, zone.position.x, zone.position.z, squads or 5)
	local ticks = 0
	while zone.owner ~= player and ticks < 200 do
		TD_Zones.Update(players, ticks)
		ticks = ticks + 1
	end
	Mock.ClearSquads(player)
	return ticks
end

-----------------------------------------------------------------------------
section("Zone layout")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()

local zoneCount = #TD_Zones.list
check("places a reasonable number of zones", zoneCount >= 10 and zoneCount <= 20,
	"got " .. zoneCount)
check("skips unplayable terrain", zoneCount < 20, "got " .. zoneCount)

local seen, minV, maxV = {}, math.huge, -1
for _, z in ipairs(TD_Zones.list) do
	seen[z.value] = true
	minV, maxV = math.min(minV, z.value), math.max(maxV, z.value)
end
local tiers = 0
for _ in pairs(seen) do tiers = tiers + 1 end
check("zones span at least three value tiers", tiers >= 3, "tiers=" .. tiers)
check("value spread is meaningful", maxV - minV >= 2,
	string.format("min=%d max=%d", minV, maxV))
check("all zones start neutral", (function()
	for _, z in ipairs(TD_Zones.list) do if z.owner ~= nil then return false end end
	return true
end)())

-----------------------------------------------------------------------------
section("Capture and contest")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local zone = TD_Zones.list[1]

Mock.PlaceSquads(1, zone.position.x, zone.position.z, 2)
TD_Zones.Update({ 1, 2 }, 0)
check("presence accrues capture progress", (zone.progress[1] or 0) > 0)
check("one tick is not enough to capture", zone.owner == nil)

local ticks = 0
while zone.owner == nil and ticks < 200 do
	TD_Zones.Update({ 1, 2 }, ticks); ticks = ticks + 1
end
check("sustained presence captures the zone", zone.owner == 1, "ticks=" .. ticks)

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local big = TD_Zones.list[1]
Mock.PlaceSquads(1, big.position.x, big.position.z, 100)
TD_Zones.Update({ 1, 2 }, 0)
check("capture rate is capped against deathballs",
	(big.progress[1] or 0) <= TD_Config.captureRateCap)

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local contested = TD_Zones.list[1]
captureFor(1, contested, { 1, 2 })
check("zone is owned after sustained capture", contested.owner == 1)

Mock.PlaceSquads(1, contested.position.x, contested.position.z, 5)
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 3)
TD_Zones.Update({ 1, 2 }, 0)
check("zone is flagged contested", contested.contested == true)
check("contested zone stops paying boost", select(1, TD_Zones.GetBoostWeight(1)) == 0)
check("ownership survives a contest", contested.owner == 1)

Mock.ClearSquads(1); Mock.ClearSquads(2)
Mock.PlaceSquads(1, contested.position.x, contested.position.z, 4)
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 4)
local before = contested.progress[2] or 0
TD_Zones.Update({ 1, 2 }, 0)
check("equal presence stalls capture", (contested.progress[2] or 0) == before)

Mock.ClearSquads(1)
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 6)
local flip = 0
while contested.owner ~= 2 and flip < 200 do
	TD_Zones.Update({ 1, 2 }, flip); flip = flip + 1
end
check("superior force flips the zone", contested.owner == 2, "ticks=" .. flip)

-----------------------------------------------------------------------------
section("Income boost is a percentage of what you gather")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()

local zoneA = TD_Zones.list[1]
captureFor(1, zoneA, { 1, 2 })

-- Zone values are normalised against the map average, so one zone is worth
-- boostPerZone scaled by how it compares to a typical zone.
local expectedBoost = TD_Config.boostPerZone
	* (TD_Config.valueScalesBoost and zoneA.weight or 1)
local boost = TD_Income.GetBoost(1)
check("one zone grants the configured boost", math.abs(boost - expectedBoost) < 1e-9,
	string.format("got %.3f expected %.3f", boost, expectedBoost))

-- An AVERAGE zone must be worth exactly boostPerZone, or the headline
-- "10% per zone" is a lie.
check("the mean zone weight is exactly 1.0", (function()
	-- Weights are normalised at build time, so this must hold on any map.
	-- If it drifts, "10% per zone" stops being true.
	local sum = 0
	for _, z in ipairs(TD_Zones.list) do sum = sum + z.weight end
	return math.abs(sum / #TD_Zones.list - 1.0) < 1e-9
end)(), "mean zone weight drifted from 1.0")

-- The spread between best and worst zone must be a tilt, not a chasm.
check("weight spread stays within a reasonable band", (function()
	local lo, hi = math.huge, -math.huge
	for _, z in ipairs(TD_Zones.list) do
		lo, hi = math.min(lo, z.weight), math.max(hi, z.weight)
	end
	-- No zone should be worthless, and none should be worth two average
	-- zones on its own.
	return lo >= 0.6 and hi <= 1.5
end)(), "centre/edge weighting is too lopsided")

-- The centre premium must actually survive normalisation. It has been
-- flattened twice already by scaling bugs, so assert it directly.
check("centre zones out-earn edge zones by a clear margin", (function()
	local inner, outer = nil, nil
	for _, z in ipairs(TD_Zones.list) do
		if inner == nil or z.distance < inner.distance then inner = z end
		if outer == nil or z.distance > outer.distance then outer = z end
	end
	return inner.weight / outer.weight >= 1.4
end)(), "centre premium got flattened")
check("holding nothing grants no boost", TD_Income.GetBoost(2) == 0)

-- Gather a known amount, then pay out, and confirm the grant is a
-- percentage of what was gathered rather than a flat sum.
TD_Income.Init({ 1, 2 })
Mock.Gather(1, 1000)
TD_Income.Sample({ 1, 2 })
local stockBefore = Mock.stock[1].food
local summary = TD_Income.Payout({ 1, 2 })
local granted = Mock.stock[1].food - stockBefore

check("boost pays a percentage of gathered", granted == math.floor(1000 * boost),
	string.format("granted %d, expected %d", granted, math.floor(1000 * boost)))
check("payout summary reports the gathered amount", summary[1].gathered.food == 1000)
check("player with no zones is paid nothing", (function()
	local s = Mock.stock[2].food
	Mock.Gather(2, 1000); TD_Income.Sample({ 1, 2 }); TD_Income.Payout({ 1, 2 })
	return Mock.stock[2].food == s + 1000  -- gathered only, no bonus
end)())

-- Doubling the economy must double the payout: the boost is proportional.
TD_Income.Init({ 1, 2 })
Mock.Gather(1, 2000)
TD_Income.Sample({ 1, 2 })
local s2 = Mock.stock[1].food
TD_Income.Payout({ 1, 2 })
check("boost scales with economy size",
	Mock.stock[1].food - s2 == math.floor(2000 * boost),
	string.format("got %d", Mock.stock[1].food - s2))

-----------------------------------------------------------------------------
section("Gather measurement survives spending")
--
-- The failure this guards against: sampling stock once per payout means a
-- player who mines 800 and spends 900 reads as having gathered nothing.
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local zoneB = TD_Zones.list[1]
captureFor(1, zoneB, { 1, 2 })
TD_Income.Init({ 1, 2 })

-- Mine steadily, and spend a big lump partway through.
for i = 1, 10 do
	Mock.Gather(1, 100)
	TD_Income.Sample({ 1, 2 })
	if i == 5 then
		Mock.Spend(1, 900)
		TD_Income.Sample({ 1, 2 })
	end
end
local accrued = TD_Income.accrued[1].food
check("spending does not zero out measured gathering", accrued > 0,
	"accrued=" .. accrued)
check("measured gathering is close to what was mined",
	accrued >= 900 and accrued <= 1000,
	"accrued=" .. accrued .. " of 1000 mined")

-- The bonus itself must never be counted as gathering, or it compounds.
Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local zoneC = TD_Zones.list[1]
captureFor(1, zoneC, { 1, 2 })
TD_Income.Init({ 1, 2 })
Mock.Gather(1, 1000)
TD_Income.Sample({ 1, 2 })
TD_Income.Payout({ 1, 2 })
TD_Income.Sample({ 1, 2 })
check("granted bonus is not counted as gathering", TD_Income.accrued[1].food == 0,
	"accrued=" .. TD_Income.accrued[1].food)

-- The cumulative-stats path should agree with the sampled path.
Mock.Reset(2); loadMod(); Mock.EnableCumulativeStats(); TerritoryDomination_OnInit()
local zoneD = TD_Zones.list[1]
captureFor(1, zoneD, { 1, 2 })
TD_Income.Init({ 1, 2 })
check("cumulative stats are detected when available", TD_Income.usingCumulative == true)
Mock.Gather(1, 1000)
Mock.Spend(1, 5000)   -- spending must be irrelevant on this path
TD_Income.Sample({ 1, 2 })
check("cumulative path ignores spending entirely", TD_Income.accrued[1].food == 1000,
	"accrued=" .. TD_Income.accrued[1].food)

-- The total boost must be capped so a runaway leader cannot compound.
Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
for _, z in ipairs(TD_Zones.list) do z.owner = 1 end
local capped = TD_Income.GetBoost(1)
check("total boost is capped", capped <= TD_Config.maxTotalBoost + 1e-9,
	string.format("got %.2f cap %.2f", capped, TD_Config.maxTotalBoost))

-----------------------------------------------------------------------------
section("Conquest: killing a player takes their zones")
-----------------------------------------------------------------------------

Mock.Reset(3); loadMod(); TerritoryDomination_OnInit()
local players = { 1, 2, 3 }

-- Player 2 builds an empire of three zones.
for i = 1, 3 do captureFor(2, TD_Zones.list[i], players) end
check("victim holds zones before dying", TD_Zones.CountOwned(2) == 3)

-- Player 1 grinds two of them away, establishing them as the aggressor.
captureFor(1, TD_Zones.list[1], players)
captureFor(1, TD_Zones.list[2], players)
check("ledger records who took territory",
	TD_Zones.ledger[2] ~= nil and TD_Zones.ledger[2][1] == 2,
	"ledger=" .. tostring(TD_Zones.ledger[2] and TD_Zones.ledger[2][1]))

-- Player 3 snipes nothing; player 2 dies.
local remaining = TD_Zones.CountOwned(2)
Mock.Kill(2)
TerritoryDomination_EliminationCheck()

check("conqueror inherits the dead player's zones",
	TD_Zones.CountOwned(1) >= 2 + remaining,
	string.format("P1 now holds %d", TD_Zones.CountOwned(1)))
check("dead player holds nothing", TD_Zones.CountOwned(2) == 0)
check("uninvolved player gains nothing", TD_Zones.CountOwned(3) == 0)
check("inherited zones are fully captured, not half-taken", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.owner == 1 and (z.progress[1] or 0) < TD_Config.captureThreshold then
			return false
		end
	end
	return true
end)())
check("conquest is announced", (function()
	for _, m in ipairs(Mock.messages) do
		if m:find("claims their") then return true end
	end
	return false
end)())

-- With no aggressor on record, zones go neutral rather than to a bystander.
Mock.Reset(3); loadMod(); TerritoryDomination_OnInit()
captureFor(3, TD_Zones.list[1], { 1, 2, 3 })
Mock.Kill(3)
TerritoryDomination_EliminationCheck()
check("an unattributed death releases zones to neutral",
	TD_Zones.list[1].owner == nil,
	"owner=" .. tostring(TD_Zones.list[1].owner))

-----------------------------------------------------------------------------
section("Win condition: last player standing")
-----------------------------------------------------------------------------

Mock.Reset(3); loadMod(); TerritoryDomination_OnInit(); TerritoryDomination_Start()

Mock.Advance(120)
check("match does not end while three players live", Mock.winner == nil)

Mock.Kill(3)
Mock.Advance(30)
check("match does not end with two players left", Mock.winner == nil,
	"winner=" .. tostring(Mock.winner))

Mock.Kill(2)
Mock.Advance(30)
check("last player standing wins", Mock.winner == 1, "winner=" .. tostring(Mock.winner))
check("rules stop after the win", (function()
	for _, r in ipairs(Mock.rules) do if not r.removed then return false end end
	return true
end)())
check("there is no score cap or timer that could end it early", (function()
	return TD_Config.scoreCap == nil and TD_Config.timeLimit == nil
end)())

-- A long game with a big economy must still not end on its own.
Mock.Reset(2); loadMod(); TerritoryDomination_OnInit(); TerritoryDomination_Start()
captureFor(1, TD_Zones.list[1], { 1, 2 })
Mock.AdvanceGathering(1800, 20, { 1, 2 })   -- 30 minutes of heavy gathering
check("a 30 minute game with income flowing does not auto-end", Mock.winner == nil)
check("the boosted player did receive income", Mock.stock[1].food > 0)

-----------------------------------------------------------------------------
section("Starting zones")
-----------------------------------------------------------------------------

Mock.Reset(4); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()

check("every player starts owning exactly one zone", (function()
	for _, p in ipairs({ 1, 2, 3, 4 }) do
		if TD_Zones.CountOwned(p) ~= 1 then return false end
	end
	return true
end)(), (function()
	local t = {}
	for _, p in ipairs({ 1, 2, 3, 4 }) do table.insert(t, TD_Zones.CountOwned(p)) end
	return "owned: " .. table.concat(t, ",")
end)())

check("starting zones are all different", (function()
	local owners = {}
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then
			if owners[z.owner] then return false end
			owners[z.owner] = true
		end
	end
	return true
end)())

check("most of the map is still neutral at the start", (function()
	local owned = 0
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then owned = owned + 1 end
	end
	return owned == 4
end)())

check("starting zones are fully captured, not half-taken", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil and (z.progress[z.owner] or 0) < TD_Config.captureThreshold then
			return false
		end
	end
	return true
end)())

check("a starting zone cannot be walked into and flipped instantly", (function()
	-- An opponent must still do the full capture work to take it.
	local mine = nil
	for _, z in ipairs(TD_Zones.list) do
		if z.owner == 1 then mine = z end
	end
	Mock.PlaceSquads(2, mine.position.x, mine.position.z, 5)
	TD_Zones.Update({ 1, 2, 3, 4 }, 0)
	return mine.owner == 1
end)())

Mock.Reset(4); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()

check("each player's starting zone is the nearest one to their base", (function()
	for _, player in ipairs({ 1, 2, 3, 4 }) do
		local mine, mineDist = nil, nil
		local start = Mock.starts[player]
		for _, z in ipairs(TD_Zones.list) do
			if z.owner == player then
				mine = z
				mineDist = math.sqrt((z.position.x - start.x) ^ 2 + (z.position.z - start.z) ^ 2)
			end
		end
		if mine == nil then return false end
		-- No unowned zone may be dramatically closer than the one assigned.
		for _, z in ipairs(TD_Zones.list) do
			if z.owner == nil then
				local d = math.sqrt((z.position.x - start.x) ^ 2 + (z.position.z - start.z) ^ 2)
				if d < mineDist * 0.5 then return false end
			end
		end
	end
	return true
end)(), "a player was given a zone far from their base")

check("no player starts with a materially better zone than another", (function()
	local lo, hi = math.huge, -math.huge
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then
			lo, hi = math.min(lo, z.weight), math.max(hi, z.weight)
		end
	end
	-- Within 25% of each other: starting income must not be a coin flip.
	return hi / lo <= 1.25
end)(), "starting zone weights are unfairly spread")

check("everyone starts on roughly the same income boost", (function()
	local lo, hi = math.huge, -math.huge
	for _, p in ipairs({ 1, 2, 3, 4 }) do
		local b = TD_Income.GetBoost(p)
		lo, hi = math.min(lo, b), math.max(hi, b)
	end
	return lo > 0 and hi / lo <= 1.25
end)())

-- Nobody may be stranded with a zone they cannot realistically defend.
-- This regressed once: a hard fairness filter shrank the candidate pool
-- until one player was handed a fair-but-distant zone across the map.
check("no player is stranded far from their starting zone", (function()
	local distances = {}
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then
			local st = Mock.starts[z.owner]
			table.insert(distances,
				math.sqrt((z.position.x - st.x) ^ 2 + (z.position.z - st.z) ^ 2))
		end
	end
	table.sort(distances)
	local median = distances[math.ceil(#distances / 2)]
	return distances[#distances] <= median * 3
end)(), "one player's starting zone is far outside the normal range")

-- A crowded FFA is the hardest case for fair assignment; it must still
-- give everyone exactly one zone and keep starting income comparable.
Mock.Reset(8); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()
check("an 8 player FFA still gives everyone exactly one zone", (function()
	for p = 1, 8 do
		if TD_Zones.CountOwned(p) ~= 1 then return false end
	end
	return true
end)())
check("starting income stays comparable even in a crowded FFA", (function()
	local lo, hi = math.huge, -math.huge
	for p = 1, 8 do
		local b = TD_Income.GetBoost(p)
		lo, hi = math.min(lo, b), math.max(hi, b)
	end
	return lo > 0 and hi / lo <= 1.5
end)(), "starting boosts are too unevenly spread")

-- Two players crammed into the same corner must still get one zone each.
Mock.Reset(2); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetStart(1, -100, -100)
Mock.SetStart(2, -95, -100)   -- almost on top of player 1
TerritoryDomination_OnInit()
check("players starting close together still get separate zones",
	TD_Zones.CountOwned(1) == 1 and TD_Zones.CountOwned(2) == 1,
	string.format("P1=%d P2=%d", TD_Zones.CountOwned(1), TD_Zones.CountOwned(2)))

-- With no starting positions available, fall back to spreading them out.
Mock.Reset(4); loadMod()
TD_Config.startingZonePerPlayer = true
-- deliberately no Mock.SetSymmetricStarts
TerritoryDomination_OnInit()
check("falls back gracefully when starting positions are unavailable", (function()
	for _, p in ipairs({ 1, 2, 3, 4 }) do
		if TD_Zones.CountOwned(p) ~= 1 then return false end
	end
	return true
end)())
check("fallback spreads players apart rather than stacking them", (function()
	local owned = {}
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then table.insert(owned, z) end
	end
	-- No two starting zones adjacent enough to share a capture radius.
	for i = 1, #owned do
		for j = i + 1, #owned do
			local dx = owned[i].position.x - owned[j].position.x
			local dz = owned[i].position.z - owned[j].position.z
			if math.sqrt(dx * dx + dz * dz) < TD_Config.zoneRadius * 2 then
				return false
			end
		end
	end
	return true
end)())

-- And the feature can be turned off entirely.
Mock.Reset(4); loadMod()
TD_Config.startingZonePerPlayer = false
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()
check("starting zones can be disabled", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then return false end
	end
	return true
end)())

-----------------------------------------------------------------------------
section("Visuals")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()

local rings, blips = 0, 0
for _, v in ipairs(Mock.visuals) do
	if v.kind == "decal" then rings = rings + 1 end
	if v.kind == "blip" then blips = blips + 1 end
end
check("every zone gets a minimap blip", blips == #TD_Zones.list,
	string.format("%d blips for %d zones", blips, #TD_Zones.list))
check("every zone gets ground visuals", rings >= #TD_Zones.list,
	string.format("%d decals for %d zones", rings, #TD_Zones.list))

check("neutral zones render below full opacity", (function()
	for _, v in ipairs(Mock.visuals) do
		if v.kind == "decal" and v.opacity ~= nil and v.opacity >= 1.0 then
			return false
		end
	end
	return true
end)())
check("resting opacity is unobtrusive", TD_Config.visuals.ringOpacity <= 0.5,
	"opacity=" .. TD_Config.visuals.ringOpacity)

local visZone = TD_Zones.list[1]
captureFor(1, visZone, { 1, 2 })
local ownerColour = Player_GetUIColour(1)
check("captured zone takes the owner's colour",
	visZone.ring ~= nil and visZone.ring.colour.r == ownerColour.r,
	"colour did not update")
check("capture pulses to full opacity", visZone.ring.opacity == 1.0,
	"opacity=" .. tostring(visZone.ring.opacity))

-- The pulse must settle back down rather than staying bright.
TD_Visuals.Update(1000)
check("pulse settles back to resting opacity",
	visZone.ring.opacity == TD_Config.visuals.ringOpacity,
	"opacity=" .. tostring(visZone.ring.opacity))

-- Contested zones are more visible, but still not full brightness.
Mock.PlaceSquads(1, visZone.position.x, visZone.position.z, 3)
Mock.PlaceSquads(2, visZone.position.x, visZone.position.z, 2)
TD_Zones.Update({ 1, 2 }, 2000)
check("contested zones are more visible than resting",
	visZone.ring.opacity > TD_Config.visuals.ringOpacity)
check("contested zones are still not full brightness",
	visZone.ring.opacity < 1.0)

check("visuals can be disabled wholesale", (function()
	Mock.Reset(2); loadMod()
	TD_Config.visuals.enabled = false
	TerritoryDomination_OnInit()
	return #Mock.visuals == 0
end)())

-----------------------------------------------------------------------------
print(string.format("\n%d passed, %d failed", passed, failed))
os.exit(failed == 0 and 0 or 1)
