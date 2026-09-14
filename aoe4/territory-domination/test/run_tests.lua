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
	dofile("scar/td_king.scar")
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
-- Cells tile the map, so impassable ground is KEPT as a zone rather than
-- punching a hole in the grid. It simply stays neutral, since nobody can
-- stand in it.
check("impassable cells stay in the grid rather than leaving holes",
	zoneCount == TD_Zones.cols * TD_Zones.rows,
	string.format("%d zones for a %dx%d grid", zoneCount, TD_Zones.cols, TD_Zones.rows))
check("impassable cells are flagged", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.impassable then return true end
	end
	return false
end)())

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
local ceiling = TD_Zones.TotalWeight() * TD_Config.boostPerZone
	* TD_Config.maxTotalBoostFraction
check("total boost is capped", capped <= ceiling + 1e-9,
	string.format("got %.2f cap %.2f", capped, ceiling))

-- The cap is a share of the map, not a fixed percentage, so it must mean
-- the same thing on a small map and a large one. A fixed cap would bind at
-- a quarter of a 72 zone map and make most of it worthless.
check("the cap scales with zone count rather than being fixed", (function()
	local function capAtPlayerCount(players)
		Mock.Reset(players); loadMod(); TerritoryDomination_OnInit()
		for _, z in ipairs(TD_Zones.list) do z.owner = 1 end
		-- Boost when holding the whole map, and the share at which it binds.
		local whole = TD_Income.GetBoost(1)
		return whole, #TD_Zones.list
	end
	local smallBoost, smallZones = capAtPlayerCount(2)
	local bigBoost, bigZones = capAtPlayerCount(8)
	if bigZones <= smallZones then return false end
	-- A bigger map must allow a strictly bigger maximum boost.
	return bigBoost > smallBoost
end)(), "cap did not scale with map size")

-----------------------------------------------------------------------------
section("Zone count scales with map and players")
-----------------------------------------------------------------------------

local function zonesFor(players, mapSize)
	Mock.Reset(players, mapSize); loadMod(); TerritoryDomination_OnInit()
	return #TD_Zones.list
end

local zones2 = zonesFor(2, 400)
local zones4 = zonesFor(4, 560)
local zones8 = zonesFor(8, 800)

check("a 1v1 map keeps the density the mode was balanced at",
	zones2 >= 15 and zones2 <= 20, "got " .. zones2)
check("a 4 player map has more zones than a 1v1", zones4 > zones2,
	string.format("2p=%d 4p=%d", zones2, zones4))
check("an 8 player map has more zones than a 4 player", zones8 > zones4,
	string.format("4p=%d 8p=%d", zones4, zones8))
check("8 players get roughly four times a 1v1", zones8 >= zones2 * 3,
	string.format("2p=%d 8p=%d", zones2, zones8))

check("zones per player stays roughly constant across sizes", (function()
	local a, b = zones2 / 2, zones8 / 8
	return math.abs(a - b) / a <= 0.35
end)(), string.format("2p=%.1f/head 8p=%.1f/head", zones2 / 2, zones8 / 8))

check("a big map gets more zones even with few players",
	zonesFor(2, 800) > zones2,
	string.format("small=%d big=%d", zones2, zonesFor(2, 800)))

check("zone count is bounded for performance", (function()
	return zonesFor(8, 4000) <= TD_Config.maxZoneCount
end)())

check("a tiny map still gets a playable number of zones", (function()
	return zonesFor(2, 60) >= TD_Config.minZoneCount - 4
end)(), "got " .. zonesFor(2, 60))

check("zone count can be forced explicitly", (function()
	Mock.Reset(4, 800); loadMod()
	TD_Config.zoneCountOverride = 12
	TerritoryDomination_OnInit()
	-- Grid fitting and dropped cells mean this is approximate, not exact.
	return #TD_Zones.list >= 8 and #TD_Zones.list <= 12
end)())

-----------------------------------------------------------------------------
section("Large maps stagger scans without changing capture speed")
-----------------------------------------------------------------------------

-- The whole point of crediting capture by elapsed time: a zone on a big map
-- is examined every few ticks, not every tick, and must still take 20s.
local function soloCaptureSeconds(players, mapSize)
	Mock.Reset(players, mapSize); loadMod(); TerritoryDomination_OnInit()
	local z = TD_Zones.list[1]
	Mock.PlaceSquads(1, z.position.x, z.position.z, 1)
	local t = 0
	while z.owner == nil and t < 400 do
		t = t + TD_Config.scanInterval
		TD_Zones.Update({ 1, 2 }, t)
	end
	return t, #TD_Zones.list
end

local smallTime, smallCount = soloCaptureSeconds(2, 400)
local bigTime, bigCount = soloCaptureSeconds(8, 800)

check("one unit still captures in 20s on a small map",
	math.abs(smallTime - 20) <= TD_Config.scanInterval,
	string.format("%.1fs over %d zones", smallTime, smallCount))
check("one unit still captures in about 20s on a big staggered map",
	math.abs(bigTime - 20) <= TD_Config.scanInterval * 3,
	string.format("%.1fs over %d zones", bigTime, bigCount))

check("the scan budget is actually being applied on a big map",
	bigCount > TD_Config.zoneScanBudget,
	string.format("%d zones vs budget %d", bigCount, TD_Config.zoneScanBudget))

check("every zone gets scanned rather than only the first batch", (function()
	Mock.Reset(8, 800); loadMod(); TerritoryDomination_OnInit()
	local t = 0
	-- Enough ticks to cycle the cursor right round the list several times.
	for _ = 1, #TD_Zones.list * 3 do
		t = t + TD_Config.scanInterval
		TD_Zones.Update({ 1, 2, 3, 4, 5, 6, 7, 8 }, t)
	end
	for _, z in ipairs(TD_Zones.list) do
		if z.lastScan == nil then return false end
	end
	return true
end)(), "some zones were never examined")

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
			if math.sqrt(dx * dx + dz * dz) < owned[i].halfW * 2 then
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
section("Capture timing: one unit, twenty seconds")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()

-- The headline rule: a single unit walking in takes exactly 20 seconds.
local timed = TD_Zones.list[1]
Mock.PlaceSquads(1, timed.position.x, timed.position.z, 1)
local seconds = 0
while timed.owner == nil and seconds < 200 do
	TD_Zones.Update({ 1, 2 }, seconds)
	seconds = seconds + TD_Config.scanInterval
end
check("one unit captures a zone in exactly 20 seconds", math.abs(seconds - 20) < 1e-9,
	string.format("took %.1fs", seconds))

-- More units help, but within a bounded range.
local function captureSeconds(squads)
	Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
	local z = TD_Zones.list[1]
	Mock.PlaceSquads(1, z.position.x, z.position.z, squads)
	local t = 0
	while z.owner == nil and t < 200 do
		TD_Zones.Update({ 1, 2 }, t)
		t = t + TD_Config.scanInterval
	end
	return t
end

local twoUnits, threeUnits, manyUnits = captureSeconds(2), captureSeconds(3), captureSeconds(40)
check("two units capture faster than one", twoUnits < 20,
	string.format("%.1fs", twoUnits))
check("a 40 unit army is no faster than three", manyUnits == threeUnits,
	string.format("40 units %.1fs vs 3 units %.1fs", manyUnits, threeUnits))
check("even a huge army cannot capture instantly", manyUnits >= 5,
	string.format("%.1fs", manyUnits))

-----------------------------------------------------------------------------
section("Square grid")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()

check("every cell is the same size", (function()
	local w, h = TD_Zones.list[1].halfW, TD_Zones.list[1].halfH
	for _, z in ipairs(TD_Zones.list) do
		if math.abs(z.halfW - w) > 1e-9 or math.abs(z.halfH - h) > 1e-9 then
			return false
		end
	end
	return true
end)())

check("cells are square, not oblong", (function()
	local z = TD_Zones.list[1]
	return math.abs(z.halfW - z.halfH) < 1e-9
end)(), string.format("%.1f x %.1f", TD_Zones.list[1].halfW * 2, TD_Zones.list[1].halfH * 2))

check("the grid covers every cell of its own dimensions",
	#TD_Zones.list == TD_Zones.cols * TD_Zones.rows,
	string.format("%d zones, %dx%d grid",
		#TD_Zones.list, TD_Zones.cols, TD_Zones.rows))

-- Tiling means neighbours share an edge exactly: centres are one full cell
-- apart, no more (a gap) and no less (an overlap).
check("horizontal neighbours share an edge exactly", (function()
	for _, z in ipairs(TD_Zones.list) do
		local right = TD_Zones.byCell[(z.col + 1) .. ":" .. z.row]
		if right ~= nil then
			if math.abs((right.position.x - z.position.x) - z.halfW * 2) > 1e-6 then
				return false
			end
			if math.abs(right.position.z - z.position.z) > 1e-6 then return false end
		end
	end
	return true
end)(), "cells do not tile cleanly on the x axis")

check("vertical neighbours share an edge exactly", (function()
	for _, z in ipairs(TD_Zones.list) do
		local below = TD_Zones.byCell[z.col .. ":" .. (z.row + 1)]
		if below ~= nil then
			if math.abs((below.position.z - z.position.z) - z.halfH * 2) > 1e-6 then
				return false
			end
		end
	end
	return true
end)(), "cells do not tile cleanly on the z axis")

check("no point on the grid falls in two zones at once", (function()
	-- Sample a lattice of points and count how many cells claim each.
	local z0 = TD_Zones.list[1]
	for _, probe in ipairs(TD_Zones.list) do
		for _, off in ipairs({ { 0, 0 }, { 0.4, 0.4 }, { -0.4, 0.4 } }) do
			local px = probe.position.x + off[1] * z0.halfW
			local pz = probe.position.z + off[2] * z0.halfH
			local claims = 0
			for _, z in ipairs(TD_Zones.list) do
				if math.abs(px - z.position.x) <= z.halfW + 1e-9
					and math.abs(pz - z.position.z) <= z.halfH + 1e-9 then
					claims = claims + 1
				end
			end
			if claims ~= 1 then return false end
		end
	end
	return true
end)(), "cells overlap or leave gaps")

check("an interior cell has four neighbours", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.col > 0 and z.col < TD_Zones.cols - 1
			and z.row > 0 and z.row < TD_Zones.rows - 1 then
			return #TD_Zones.GetNeighbours(z) == 4
		end
	end
	return false
end)())

check("a corner cell has two neighbours", (function()
	local corner = TD_Zones.byCell["0:0"]
	return corner ~= nil and #TD_Zones.GetNeighbours(corner) == 2
end)())

check("friendly neighbours are counted for a held block", (function()
	local centre = nil
	for _, z in ipairs(TD_Zones.list) do
		if z.col == 1 and z.row == 1 then centre = z end
	end
	if centre == nil then return false end
	for _, n in ipairs(TD_Zones.GetNeighbours(centre)) do n.owner = 1 end
	return TD_Zones.CountFriendlyNeighbours(centre, 1) == #TD_Zones.GetNeighbours(centre)
end)())

-- The square cell test must reject a unit standing in a neighbour's corner,
-- which a plain circular query around the cell centre would wrongly include.
Mock.Reset(2); loadMod(); TerritoryDomination_OnInit()
local cellA = TD_Zones.byCell["1:1"]
local cornerX = cellA.position.x + cellA.halfW * 1.6
local cornerZ = cellA.position.z + cellA.halfH * 1.6
Mock.PlaceSquads(1, cornerX, cornerZ, 3)
check("a unit in a neighbouring cell does not count toward this one",
	TD_API.CountPlayerSquadsInCell(1, cellA.position, cellA.halfW, cellA.halfH) == 0,
	"circular bleed across the cell corner")
check("a unit inside the cell does count", (function()
	Mock.ClearSquads(1)
	Mock.PlaceSquads(1, cellA.position.x + cellA.halfW * 0.8, cellA.position.z, 2)
	return TD_API.CountPlayerSquadsInCell(1, cellA.position, cellA.halfW, cellA.halfH) == 2
end)())

check("falls back to a circular test when the group cannot be walked", (function()
	Mock.ClearSquads(1)
	Mock.PlaceSquads(1, cellA.position.x, cellA.position.z, 4)
	Mock.blockSquadWalk = true
	local n = TD_API.CountPlayerSquadsInCell(1, cellA.position, cellA.halfW, cellA.halfH)
	Mock.blockSquadWalk = false
	return n == 4
end)(), "fallback path did not return a usable count")

-- Tiling means there is no neutral ground: a unit anywhere is always in a
-- zone, which is the whole point of the change.
check("every point on the map belongs to some zone", (function()
	local span = TD_Zones.cols * TD_Zones.list[1].halfW * 2
	for _, frac in ipairs({ -0.45, -0.2, 0, 0.2, 0.45 }) do
		local px, pz = span * frac, span * frac
		local found = false
		for _, z in ipairs(TD_Zones.list) do
			if math.abs(px - z.position.x) <= z.halfW + 1e-9
				and math.abs(pz - z.position.z) <= z.halfH + 1e-9 then
				found = true; break
			end
		end
		if not found then return false end
	end
	return true
end)(), "found a point inside the grid that no zone covers")

-----------------------------------------------------------------------------
section("Water zones")
-----------------------------------------------------------------------------

Mock.Reset(2); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()

local waterZones, landZones = {}, {}
for _, z in ipairs(TD_Zones.list) do
	table.insert(z.isWater and waterZones or landZones, z)
end

check("water is turned into zones rather than skipped", #waterZones > 0,
	"no water zones were created")
check("land zones still exist alongside them", #landZones > 0)
check("water zones are named distinctly", waterZones[1].name:find("Waters") ~= nil,
	"name=" .. waterZones[1].name)

-- A warship sitting in open water captures it exactly like a land zone.
local sea = waterZones[1]
Mock.PlaceSquads(2, sea.position.x, sea.position.z, 1)
local navalSeconds = 0
while sea.owner == nil and navalSeconds < 200 do
	TD_Zones.Update({ 1, 2 }, navalSeconds)
	navalSeconds = navalSeconds + TD_Config.scanInterval
end
check("a unit in open water captures it on the same 20 second rule",
	math.abs(navalSeconds - 20) < 1e-9, string.format("took %.1fs", navalSeconds))
check("captured water zones pay an income boost", TD_Income.GetBoost(2) > 0)

check("nobody is given a water zone to start on", (function()
	-- Fresh init: the naval capture above deliberately owns a sea zone, so
	-- this has to look at a map nobody has played on yet.
	Mock.Reset(4); loadMod()
	TD_Config.startingZonePerPlayer = true
	Mock.SetSymmetricStarts(0.7)
	TerritoryDomination_OnInit()
	local assigned = 0
	for _, z in ipairs(TD_Zones.list) do
		if z.owner ~= nil then
			assigned = assigned + 1
			if z.isWater then return false end
		end
	end
	return assigned == 4
end)(), "a player started on water they cannot reach")

-- And water zones can be switched off entirely.
Mock.Reset(2); loadMod()
TD_Config.includeWaterZones = false
TerritoryDomination_OnInit()
check("water zones can be disabled", (function()
	for _, z in ipairs(TD_Zones.list) do
		if z.isWater then return false end
	end
	return true
end)())

-----------------------------------------------------------------------------
section("Kings")
-----------------------------------------------------------------------------

Mock.Reset(3); loadMod()
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()

check("every player gets a king", (function()
	for p = 1, 3 do
		if TD_King.kings[p] == nil then return false end
	end
	return true
end)())
check("kings belong to the right players", (function()
	for p = 1, 3 do
		if TD_King.kings[p].handle.player ~= p then return false end
	end
	return true
end)())
check("kings spawn near their owner's base", (function()
	for p = 1, 3 do
		local pos = TD_King.kings[p].handle.pos
		local st = Mock.starts[p]
		local d = math.sqrt((pos.x - st.x) ^ 2 + (pos.z - st.z) ^ 2)
		if d > TD_Config.kings.spawnOffset * 2 then return false end
	end
	return true
end)())
check("all kings start alive", TD_King.IsAlive(1) and TD_King.IsAlive(2))

-- Regicide: killing a king eliminates its owner.
Mock.Reset(3); loadMod()
TD_Config.startingZonePerPlayer = true
Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit(); TerritoryDomination_Start()

check("player is alive before losing their king", TerritoryDomination.alive[2] == true)
Mock.KillKing(2)
TerritoryDomination_EliminationCheck()
check("losing your king eliminates you", TerritoryDomination.alive[2] == false)
check("a king's death is announced", (function()
	for _, m in ipairs(Mock.messages) do
		if m:find("king has fallen") then return true end
	end
	return false
end)())
check("the match does not end while two players remain", Mock.winner == nil)

-- Regicide must feed the normal conquest path, not bypass it.
Mock.Reset(3); loadMod(); Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit(); TerritoryDomination_Start()
local players3 = { 1, 2, 3 }
for i = 1, 3 do captureFor(2, TD_Zones.list[i], players3) end
captureFor(1, TD_Zones.list[1], players3)
captureFor(1, TD_Zones.list[2], players3)
local heldByVictim = TD_Zones.CountOwned(2)
local heldByKiller = TD_Zones.CountOwned(1)
Mock.KillKing(2)
TerritoryDomination_EliminationCheck()
check("regicide transfers the victim's zones to their conqueror",
	TD_Zones.CountOwned(1) == heldByKiller + heldByVictim,
	string.format("P1 %d -> %d, victim held %d",
		heldByKiller, TD_Zones.CountOwned(1), heldByVictim))

-- Last king standing wins the match.
Mock.Reset(3); loadMod(); Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit(); TerritoryDomination_Start()
Mock.KillKing(2); Mock.KillKing(3)
Mock.Advance(30)
check("the last player with a king wins", Mock.winner == 1,
	"winner=" .. tostring(Mock.winner))

-- A wounded king warns its owner before it is too late.
Mock.Reset(2); loadMod(); Mock.SetSymmetricStarts(0.7)
TerritoryDomination_OnInit()
Mock.SetKingHealth(1, TD_Config.kings.lowHealthWarning - 0.05)
TD_King.Update()
check("a badly wounded king warns its owner", (function()
	for _, m in ipairs(Mock.messages) do
		if m:find("gravely wounded") then return true end
	end
	return false
end)())
check("the warning fires only once", (function()
	local count = 0
	TD_King.Update(); TD_King.Update()
	for _, m in ipairs(Mock.messages) do
		if m:find("gravely wounded") then count = count + 1 end
	end
	return count == 1
end)())

-- Regicide can be turned off without removing kings.
Mock.Reset(2); loadMod(); Mock.SetSymmetricStarts(0.7)
TD_Config.kings.kingDeathEliminates = false
TerritoryDomination_OnInit(); TerritoryDomination_Start()
Mock.KillKing(2)
TerritoryDomination_EliminationCheck()
check("decorative kings do not eliminate their owner",
	TerritoryDomination.alive[2] == true)

-- Kings can be disabled entirely.
Mock.Reset(2); loadMod(); Mock.SetSymmetricStarts(0.7)
TD_Config.kings.enabled = false
TerritoryDomination_OnInit()
check("kings can be disabled", next(TD_King.kings) == nil)

-- If kings only spawn for some players, regicide must switch itself off
-- rather than run a match where one player is immune to it.
Mock.Reset(3); loadMod()
Mock.SetSymmetricStarts(0.7)
Mock.spawnBlockedFor = 3
TerritoryDomination_OnInit()
check("partial king spawn disables regicide rather than playing unfairly",
	TD_Config.kings.kingDeathEliminates == false)
Mock.spawnBlockedFor = nil

-- No starting positions means no kings, and regicide must then not be able
-- to eliminate anybody. Found by four tests failing for exactly this reason.
Mock.Reset(2); loadMod()
-- deliberately no Mock.SetSymmetricStarts
TerritoryDomination_OnInit(); TerritoryDomination_Start()
check("no kings spawn when starting positions are unavailable",
	next(TD_King.kings) == nil)
Mock.Advance(30)
check("a kingless match does not eliminate anyone by regicide",
	TerritoryDomination.alive[1] and TerritoryDomination.alive[2])

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
