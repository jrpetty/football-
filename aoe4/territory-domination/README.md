# Territory Domination

An Age of Empires IV game mode. The map is divided into ~20 zones. **Every
zone you hold adds 10% to your gather rate.** The match runs until one player
is left standing — no score cap, no timer.

When you eliminate a rival, **you inherit their zones.** Killing someone
hands you their economy, so every elimination accelerates the endgame instead
of resetting it.

The design goal is that **economy and combat are the same action**. Edge zones
pay least, so booming in a safe corner is unprofitable. An enemy standing in
your zone freezes its boost immediately, so a cheap raid has instant economic
value. And because the boost is a percentage rather than a flat handout, zones
stay worth fighting over at every stage of the game.

## How it plays

- ~20 circular zones on a grid. Cells over water or impassable terrain are
  dropped, so expect 15–20 on most maps.
- **Standing in a zone captures it** — ~17 seconds with four squads, ~67
  seconds with one.
- Capture rate is **capped**, so a 20-unit deathball takes a zone no faster
  than four squads do. Splitting your army is viable.
- **Centre zones pay ~14%, edge zones ~7%**, with an average zone at exactly
  10%. The middle of the map is worth twice the rim.
- An enemy in your zone **freezes its boost immediately**, before they have
  captured anything.
- Equal numbers on both sides **stall** the capture entirely. Zones flip only
  when someone genuinely wins the fight.
- **Eliminated players' zones transfer to their conqueror**, arriving fully
  captured.

### Why a percentage and not flat income

Flat income is worth most to whoever is losing and becomes irrelevant by the
late game. A percentage scales with the economy you already have, so a zone is
worth taking whether you are ahead or behind, and a large economy has more to
protect. From `balance_sim.lua`:

| Zones held | Boost | At 1000 res/min | At 3000 res/min |
|---|---|---|---|
| 1 | +10% | +100 | +300 |
| 3 | +30% | +300 | +900 |
| 5 | +50% | +500 | +1500 |
| 8 | +80% | +800 | +2400 |

Total boost is capped at +200% so a player who has already taken most of the
map cannot compound out of reach.

### Who counts as your killer

Engine-level kill attribution for a whole *player* is not reliably exposed, so
the mode keeps a **conquest ledger**: a tally of how many zones each player has
taken from each other player. Whoever has taken the most territory from you is
treated as your conqueror.

In practice that is the player who actually ground you down — and unlike
last-hit attribution, it cannot be stolen by an opportunist who snipes your
final building. If nobody is on record (you resigned, or dropped), your zones
go neutral rather than to a bystander; set `neutralOnUnknownKiller = false` to
award them to the strongest survivor instead.

Zone inheritance mostly matters in FFA and team games. In a 1v1 the kill ends
the match anyway.

## Zone visuals

The brief was visible but not intrusive, which drove every choice:

- A **thin ring at the zone boundary**, not a filled disc, so the zone reads as
  an area without tinting ground you are trying to look at.
- **Low resting opacity** (0.35). Neutral zones sit lower still, so unclaimed
  ground recedes and owned ground stands out.
- **Owner's own player colour**, so no new visual vocabulary to learn.
- **Contested zones brighten** to 0.55 — noticeable, not loud.
- **Captures pulse once** to full brightness for 2 seconds, then settle. Draws
  the eye once instead of animating permanently.
- A **small minimap dot** per zone, coloured by owner, at 0.6 scale so it does
  not hide units.

Every visual parameter is under `TD_Config.visuals`. Set `enabled = false` to
turn the whole layer off; the mode plays identically without it.

## Files

```
scar/
  territorydomination.scar   Entry point, elimination, conquest, rules
  td_config.scar             All tunables. Start here when balancing.
  td_adapter.scar            Engine adapter -- see the warning below
  td_zones.scar              Zone layout, capture, ownership, conquest ledger
  td_income.scar             Gather-rate measurement and the boost payout
  td_visuals.scar            Ground rings, centre markers, minimap blips
test/
  mock_engine.lua            Stubbed Scar engine for offline testing
  run_tests.lua              55 logic tests
  balance_sim.lua            Boost curves and capture times
```

## IMPORTANT: read this before installing

**This mod has never been run inside Age of Empires IV.** It was written
without access to the game or the Essence Editor, so while the *logic* is
tested, the *engine function names* are educated guesses against a
partly-undocumented API. Expect to correct some of them.

This is why `td_adapter.scar` exists. **Every single call into the Scar API
lives in that one file.** The gameplay code never touches the engine directly.
So when something is named wrong, the fix is a one-line change in the adapter
rather than a hunt through six files.

Each adapter function is wrapped in a guarded call: a missing or renamed engine
function logs a warning and returns a safe default instead of killing the
script. The mod should *load* even with several wrong names, and tell you which
ones to fix.

### Fixing adapter mismatches

1. Load the mod in a skirmish and open the script log.
2. Look for `TD_API MISSING or FAILED: <name>`.
3. Find the real name in the Essence Editor's script browser.
4. Correct it in `td_adapter.scar` — most are in a `_tryAny({...})` list, so
   you can just add the right name to the list.

Most likely to need correcting, in rough priority order:

- **`Player_GetSquadsNearPoint`** — the unit-presence query. If this fails,
  nothing captures and the mode does nothing at all. Fix this one first.
- **`Player_GetResource` / `Player_GetResourceGathered`** — gather-rate
  measurement. If both fail, the boost measures zero and never pays out. The
  log line at startup tells you which path it chose.
- **`UI_CreateGroundDecal` / `UI_CreateMinimapBlip`** — zone visuals. Cosmetic;
  safe to leave broken while you get the rest working.
- `World_SetPlayerWin` / `Player_IsAlive` — match end and elimination
  detection. If `Player_IsAlive` fails it assumes everyone is alive, so the
  match will never end.
- `Player_GetUIColour` — zone colours fall back to white.
- `World_IsPointOverImpassableTerrain` — zones may land on water. Playable,
  just ugly.

Set `TD_API.verbose = false` in `td_adapter.scar` once you are done.

## Installing

1. Open the **Essence Editor** (Steam → Library → Tools → *Age of Empires IV —
   Mod Editors*).
2. Create a new **Game Mod**.
3. Copy the contents of `scar/` into the mod's script directory.
4. Register `territorydomination.scar` as the win condition entry point.
5. Build, launch a skirmish, watch the log, fix adapter names, repeat.

## Running the tests

Requires only a stock Lua 5.4 — no game files needed.

```sh
cd aoe4/territory-domination
lua5.4 test/run_tests.lua      # 55 logic tests
lua5.4 test/balance_sim.lua    # boost curves and capture times
```

The tests stub out the engine entirely, so they verify the *mode's logic* —
capture and contest behaviour, that the boost is a true percentage of what was
gathered, that spending does not zero out the measurement, that conquest
transfers zones to the right player, that the match ends only when one player
remains, and that visuals stay within their opacity budget.

They **cannot** verify that the engine function names are right.

## Tuning

Everything worth changing during playtesting is in `td_config.scar`:

- `boostPerZone` — the headline number. 0.10 is 10% per zone.
- `maxTotalBoost` — ceiling on a runaway leader. `nil` for uncapped.
- `centreZoneWeight` / `edgeZoneWeight` — how hard players are pulled toward
  the middle. Widening the gap makes the mode more aggressive. Weights are
  normalised so the average always lands on `boostPerZone`, whatever you set.
- `zoneCountTarget` / `zoneRadius` — zone density and how much army it takes to
  cover one.
- `captureThreshold` / `captureRatePerSquad` / `captureRateCap` — how long
  zones take to flip and how much a bigger army helps.
- `gatherSampleInterval` — how often gather rate is measured. Lower is more
  accurate when the engine lacks cumulative stats; see `td_income.scar`.
- `visuals.*` — everything about how zones are drawn.

After changing anything, run `balance_sim.lua` to see what it did before you
load the game.
