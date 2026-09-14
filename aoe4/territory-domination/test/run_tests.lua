-----------------------------------------------------------------------------
-- run_tests.lua -- logic tests for Territory Domination.
-- Run with: lua5.4 test/run_tests.lua   (from the mod root)
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

local function section(name)
	print("\n" .. name)
end

-- Load the mod fresh against the current mock state.
local function loadMod()
	dofile("scar/td_config.scar")
	dofile("scar/td_adapter.scar")
	dofile("scar/td_zones.scar")
	dofile("scar/td_score.scar")
	dofile("scar/territorydomination.scar")
	TD_API.verbose = false
end

-----------------------------------------------------------------------------
section("Zone layout")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()

local zoneCount = #TD_Zones.list
check("places a reasonable number of zones", zoneCount >= 10 and zoneCount <= 20,
	"got " .. zoneCount)
check("skips unplayable terrain", zoneCount < 20,
	"lake should have removed some cells, got " .. zoneCount)

-- Note: no zone reaches the full centreZoneValue unless a grid cell lands on
-- the exact map origin, which an even-sided grid never does. What matters is
-- that there is a real spread between the middle and the rim.
local seen, minValue, maxValue = {}, math.huge, -1
for _, z in ipairs(TD_Zones.list) do
	seen[z.value] = true
	minValue = math.min(minValue, z.value)
	maxValue = math.max(maxValue, z.value)
end
local tierCount = 0
for _ in pairs(seen) do tierCount = tierCount + 1 end
check("zones span at least three value tiers", tierCount >= 3,
	"tiers=" .. tierCount)
check("value spread is meaningful", maxValue - minValue >= 2,
	string.format("min=%d max=%d", minValue, maxValue))

-- Centre zones must be worth more than edge zones, or nothing pulls players in.
local centreZone, edgeZone = nil, nil
local bestD, worstD = math.huge, -1
for _, z in ipairs(TD_Zones.list) do
	local d = math.sqrt(z.position.x ^ 2 + z.position.z ^ 2)
	if d < bestD then bestD, centreZone = d, z end
	if d > worstD then worstD, edgeZone = d, z end
end
check("centre zone outvalues edge zone", centreZone.value > edgeZone.value,
	string.format("centre=%d edge=%d", centreZone.value, edgeZone.value))

check("all zones start neutral", (function()
	for _, z in ipairs(TD_Zones.list) do if z.owner ~= nil then return false end end
	return true
end)())

-----------------------------------------------------------------------------
section("Capture")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()

local target = TD_Zones.list[1]
Mock.PlaceSquads(1, target.position.x, target.position.z, 2)

TD_Zones.Update({ 1, 2 })
check("presence accrues capture progress", (target.progress[1] or 0) > 0,
	"progress=" .. tostring(target.progress[1]))
check("one tick is not enough to capture", target.owner == nil)

-- Enough ticks to cross the threshold.
local ticks = 0
while target.owner == nil and ticks < 50 do
	TD_Zones.Update({ 1, 2 })
	ticks = ticks + 1
end
check("sustained presence captures the zone", target.owner == 1,
	"after " .. ticks .. " ticks")

-- Capture rate is capped, so a huge army is not instant.
Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()
local z2 = TD_Zones.list[1]
Mock.PlaceSquads(1, z2.position.x, z2.position.z, 100)
TD_Zones.Update({ 1, 2 })
check("capture rate is capped against deathballs",
	(z2.progress[1] or 0) <= TD_Config.captureRateCap,
	"progress=" .. tostring(z2.progress[1]))

-----------------------------------------------------------------------------
section("Contest")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()

local contested = TD_Zones.list[1]
Mock.PlaceSquads(1, contested.position.x, contested.position.z, 5)
for _ = 1, 40 do TD_Zones.Update({ 1, 2 }) end
check("player 1 owns the zone before contest", contested.owner == 1)

local heldBefore = TD_Zones.GetHeldValue(1)
check("uncontested zone pays income", heldBefore > 0, "held=" .. heldBefore)

-- Enemy walks in.
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 3)
TD_Zones.Update({ 1, 2 })
check("zone is flagged contested", contested.contested == true)
check("contested zone stops paying", TD_Zones.GetHeldValue(1) == 0,
	"held=" .. TD_Zones.GetHeldValue(1))
check("ownership survives a contest", contested.owner == 1)

-- Equal numbers should stall the capture entirely.
Mock.ClearSquads(1); Mock.ClearSquads(2)
Mock.PlaceSquads(1, contested.position.x, contested.position.z, 4)
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 4)
local p2Before = contested.progress[2] or 0
TD_Zones.Update({ 1, 2 })
check("equal presence stalls capture", (contested.progress[2] or 0) == p2Before)

-- Enemy brings more and takes it.
Mock.ClearSquads(1)
Mock.PlaceSquads(2, contested.position.x, contested.position.z, 6)
local flipTicks = 0
while contested.owner ~= 2 and flipTicks < 60 do
	TD_Zones.Update({ 1, 2 })
	flipTicks = flipTicks + 1
end
check("superior force flips the zone", contested.owner == 2,
	"after " .. flipTicks .. " ticks")

-----------------------------------------------------------------------------
section("Income and score")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()

local zoneA = TD_Zones.list[1]
Mock.PlaceSquads(1, zoneA.position.x, zoneA.position.z, 5)
for _ = 1, 40 do TD_Zones.Update({ 1, 2 }) end

TD_Score.Tick({ 1, 2 })
check("holding a zone grants score", (TD_Score.scores[1] or 0) > 0,
	"score=" .. tostring(TD_Score.scores[1]))
check("holding a zone grants food", Mock.resources[1][RT_Food] > 0)
check("holding a zone grants gold", Mock.resources[1][RT_Gold] > 0)
check("holding nothing grants nothing", (TD_Score.scores[2] or 0) == 0)

-- Income must scale with zone value, not just zone count.
local expectedFood = TD_Config.incomePerValue.food * zoneA.value
check("income scales with zone value", Mock.resources[1][RT_Food] == expectedFood,
	string.format("got %d, expected %d", Mock.resources[1][RT_Food], expectedFood))

-----------------------------------------------------------------------------
section("Win condition")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TD_Config.scoreCap = 50  -- shorten the game so the test terminates quickly
TerritoryDomination_OnInit()
TerritoryDomination_Start()

local zoneB = TD_Zones.list[1]
Mock.PlaceSquads(1, zoneB.position.x, zoneB.position.z, 5)
Mock.Advance(600)

check("a player reaches the cap and wins", Mock.winner == 1,
	"winner=" .. tostring(Mock.winner))
check("loser is eliminated", Mock.defeated[2] == true)
check("rules stop after the win", (function()
	for _, r in ipairs(Mock.rules) do if not r.removed then return false end end
	return true
end)())
check("a win is announced", (function()
	for _, m in ipairs(Mock.messages) do
		if m:find("wins") then return true end
	end
	return false
end)())

-----------------------------------------------------------------------------
section("Elimination releases territory")
-----------------------------------------------------------------------------

Mock.Reset(2)
loadMod()
TerritoryDomination_OnInit()
local zoneC = TD_Zones.list[1]
Mock.PlaceSquads(1, zoneC.position.x, zoneC.position.z, 5)
for _ = 1, 40 do TD_Zones.Update({ 1, 2 }) end
check("zone is owned before elimination", zoneC.owner == 1)

TerritoryDomination_OnPlayerDefeated(1)
check("eliminated player's zones go neutral", zoneC.owner == nil)
check("eliminated player's progress is cleared", zoneC.progress[1] == nil)

-----------------------------------------------------------------------------
print(string.format("\n%d passed, %d failed", passed, failed))
os.exit(failed == 0 and 0 or 1)
