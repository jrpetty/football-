# Territory Domination

An Age of Empires IV win condition where the map is divided into ~20 zones.
Holding a zone pays you **both resources and score**. First to the score cap
wins.

There is no wonder, no landmark race, no hunting the last villager. The
design goal is that **economy and combat become the same action**: you cannot
boom safely in a corner, because corners are worth the least, and you cannot
turtle on a lead, because the lead is made of contested ground.

## How it plays

- The map is divided into a grid of ~20 circular zones. Cells over water or
  impassable terrain are dropped, so expect 15-20 on most maps.
- **Standing in a zone captures it.** Capture takes about 17 seconds with a
  handful of units, ~65 seconds with a single squad.
- Capture rate is **capped**, so a 40-unit deathball takes a zone barely
  faster than a raiding party of five. Splitting your army is viable.
- **Zones near the map centre are worth up to 4x an edge zone**, for both
  score and income. The middle of the map is where the game is decided.
- An enemy standing in your zone **freezes its income immediately**, before
  they have captured anything. A cheap raid has instant economic value.
- Equal numbers on both sides **stall** the capture entirely. Zones flip only
  when someone genuinely wins the fight.
- Eliminated players release their zones back to neutral rather than
  freezing them.

## Expected match length

From `test/balance_sim.lua` against the shipped config:

| Share of map value held | Time to win |
|---|---|
| 15% | ~58 min |
| 25% | ~35 min |
| 40% | ~22 min |
| 60% | ~15 min |
| 80% | ~11 min |

Zone income at a 40% share is worth roughly a dozen extra villagers — a
meaningful supplement to a normal economy, not a replacement for one.

## Files

```
scar/
  territorydomination.scar   Entry point, module registration, rule scheduling
  td_config.scar             All tunables. Start here when balancing.
  td_adapter.scar            Engine adapter -- see the warning below
  td_zones.scar              Zone layout, capture, ownership
  td_score.scar              Income, scoring, win condition
test/
  mock_engine.lua            Stubbed Scar engine for offline testing
  run_tests.lua              29 logic tests
  balance_sim.lua            Match-length and income estimator
```

## IMPORTANT: read this before installing

**This mod has never been run inside Age of Empires IV.** It was written
without access to the game or the Essence Editor, so while the *logic* is
tested, the *engine function names* are educated guesses against a
partly-undocumented API. Expect to correct some of them.

This is why `td_adapter.scar` exists. **Every single call into the Scar API
lives in that one file.** The gameplay code never touches the engine
directly. So when something is named wrong, the fix is a one-line change in
the adapter rather than a hunt through five files.

Each adapter function is also wrapped in a guarded call: a missing or
renamed engine function logs a warning and returns a safe default instead of
killing the script. So the mod should *load* even with several wrong names,
and tell you which ones to fix.

### Fixing adapter mismatches

1. Load the mod in a skirmish and open the script log.
2. Look for lines reading `TD_API MISSING or FAILED: <name>`.
3. Find the real function name in the Essence Editor's script browser.
4. Correct it in `td_adapter.scar` — most are in a `_tryAny({...})` list, so
   you can just add the right name to the list.

The functions most likely to need correcting, in rough order:

- `Player_GetSquadsNearPoint` — the unit-presence query. **This is the
  critical one**; if it fails, nothing captures and the mode does nothing.
- `UI_CreateMinimapBlip` / `UI_SetMinimapBlipOwner` — zone markers. Cosmetic,
  safe to leave broken while you get the rest working.
- `World_SetPlayerWin` / `World_SetPlayerLose` — match end. Falls back to a
  chat message announcing the winner.
- `World_IsPointOverImpassableTerrain` — terrain filtering. If it fails,
  zones may land on water; playable, just ugly.

Set `TD_API.verbose = false` in `td_adapter.scar` to silence the warnings
once you are done.

## Installing

1. Open the **Essence Editor** (Steam → Library → Tools → *Age of Empires IV
   — Mod Editors*).
2. Create a new **Game Mod**.
3. Copy the contents of `scar/` into the mod's script directory.
4. Register `territorydomination.scar` as the win condition entry point.
5. Build, then launch a skirmish with the mod enabled.
6. Watch the log, fix adapter names, repeat.

## Running the tests

Requires only a stock Lua 5.4 — no game files needed.

```sh
cd aoe4/territory-domination
lua5.4 test/run_tests.lua      # 29 logic tests
lua5.4 test/balance_sim.lua    # match length + income estimates
```

The tests stub out the engine entirely, so they verify the *mode's logic* —
that capture progresses and flips correctly, that contested zones stop
paying, that income scales with zone value, that the right player wins.
They **cannot** verify that the engine function names are right.

## Tuning

Everything you'll want to change during playtesting is in
`td_config.scar`. The parameters worth touching first:

- `scoreCap` — overall match length. Scale linearly.
- `zoneCountTarget` / `zoneRadius` — zone density and how much army it takes
  to cover one.
- `centreZoneValue` vs `edgeZoneValue` — how hard players are pulled toward
  the middle. Widening this gap makes the mode more aggressive.
- `captureThreshold` / `captureRatePerSquad` / `captureRateCap` — how long
  zones take to flip and how much a bigger army helps.
- `incomePerValue` — how much zones subsidise your economy.

After changing anything, run `balance_sim.lua` to see what it did to match
length before you load the game.
