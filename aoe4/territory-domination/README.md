# Territory Domination

An Age of Empires IV game mode. The map is divided into ~20 zones, **land and
sea alike**. Walk one unit into a zone for 20 seconds and it is yours. **Every
zone you hold adds 10% to your gather rate.**

Every player has a **king**. Lose it and you are out. The match runs until one
player is left standing — no score cap, no timer.

When you eliminate a rival, **you inherit their zones.** Killing someone
hands you their economy, so every elimination accelerates the endgame instead
of resetting it.

The design goal is that **economy and combat are the same action**. Edge zones
pay least, so booming in a safe corner is unprofitable. An enemy standing in
your zone freezes its boost immediately, so a cheap raid has instant economic
value. And because the boost is a percentage rather than a flat handout, zones
stay worth fighting over at every stage of the game.

## How it plays

- **Zone count scales with the map**: ~18 on a 1v1, ~36 on a 4-player map,
  ~66 on an 8-player one, holding roughly 9 zones per player throughout.
- **Every player starts owning the zone nearest their base**, already fully
  captured, so everyone opens on +10% rather than scrambling from zero.
- **Standing in a zone captures it.** One unit takes exactly 20 seconds, two
  take 10, three take 6.7.
- Capture rate is **capped at three units**, so a 40-unit deathball is three
  times faster than a lone scout, not forty times. Splitting your army is
  viable, and a single spare unit is always worth sending.
- **Water counts.** Sea zones are placed and captured like any other, which in
  practice means warships — naval control is worth real economy.
- **Every player has a king**, spawned at their base. Lose it and you are
  eliminated, and your zones pass to whoever took you down.
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

### Zone count

Zone count is not fixed. The target is the larger of a map-area figure and
9 zones per player, then clamped — so a big 1v1 map still gets plenty, and a
crowded small map still has enough to go round.

| Players | Map size | Zones | Per player |
|---|---|---|---|
| 2 | 400 | 18 | 9.0 |
| 3 | 480 | 26 | 8.7 |
| 4 | 560 | 36 | 9.0 |
| 6 | 690 | 54 | 9.0 |
| 8 | 800 | 66 | 8.2 |

Two things had to change to make this work:

**The boost cap.** It used to be a fixed +200%, which was fine at 18 zones
where it could never bind. On a 66-zone map it would bind at a 30% map share,
making every zone past the twentieth worthless and breaking the mode in 4v4.
It is now a fraction of the *whole map's* boost (default 0.75), so it means
the same thing at every size.

**Scan cost.** Every zone is checked against every player, so a 66-zone
8-player map would be ~530 spatial queries per second. Zones are now scanned
round-robin against a per-tick budget (default 24). This does **not** change
capture speed: progress is credited by elapsed time since that zone was last
examined, so a zone looked at every third tick gains three ticks' worth. The
only cost is that a newly arrived enemy takes up to `zones / budget` ticks to
be noticed. There is a test asserting a solo capture still takes 20 seconds on
a 66-zone map.

### Kings

Every player gets a king at their starting position. Losing it eliminates you
immediately, and elimination routes through the ordinary defeat path — so a
regicide kill hands your zones to your conqueror exactly like any other defeat.

A king below 35% health warns its owner once, so losing a match to regicide is
never a silent surprise.

**This rule was inferred, not specified.** The brief said every player has a
king but not what losing one does. Regicide is the conventional meaning and the
only reading that does anything in a last-man-standing mode. Set
`kings.kingDeathEliminates = false` to make kings decorative, or
`kings.enabled = false` to remove them.

**AoE4 has no stock king unit**, so `kings.blueprint` is a placeholder that
*will* need changing in the Essence Editor. The Mongol Khan is the closest
thing to a hero unit in the base game; any distinctive unit works, since the
mode supplies the king behaviour itself. The startup log reports how many
kings actually spawned.

If kings spawn for only *some* players, the mode **disables regicide
automatically** rather than run a match where one player is immune to it.

### Water zones

Zones are placed over water as well as land. A sea zone is captured on the same
20-second rule, which in practice means a warship parked in it, so contesting
the water is worth the same economy as contesting a hill.

Water zones are never handed out as **starting** zones regardless of settings —
a player given a sea zone they cannot reach until they have built a dock would
open a boost behind everyone else through no decision of their own.

Set `includeWaterZones = false` for a land-only map.

### Starting zones

Each player begins owning one zone — the one on their doorstep. Assignment is
resolved globally rather than per player, so two players who start close
together cannot both claim the same zone and leave one of them with scraps.

Zones are not all worth the same, so assignment applies a **soft fairness
bias**: a zone closer to average weight is preferred over a merely closer one.
It is deliberately a bias and not a filter. A hard "must be near average
weight" rule shrank the candidate pool until, in an 8-player FFA, one player
was stranded with a fair-but-distant zone 139 units from their base — a worse
unfairness than the one it fixed. As a bias, distance always dominates and
weight only breaks near-ties.

The bias value (4.0) was picked by sweeping it rather than guessing. On a
crowded 8-player layout it cuts the spread in starting income from 74% to 35%
without pushing anyone's starting zone any further from base; higher values buy
nothing more. From `balance_sim.lua`:

| Players | Starting boost | Spread | Furthest start |
|---|---|---|---|
| 2 | 9.8% – 9.8% | 0% | 26 units |
| 4 | 9.1% – 9.8% | 8% | 42 units |
| 8 | 9.0% – 12.1% | 35% | 72 units |

Eight players on an 18-zone map is the worst case — the map simply cannot give
everyone an equally-valued zone close to home. Symmetric maps with more zones
do better. Set `startingZonePerPlayer = false` to start everyone from zero
instead, or `startingZoneFairnessBias = 0` to always take the strictly nearest
zone and accept the variance.

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
  td_king.scar               King spawning, tracking and regicide
  td_visuals.scar            Ground rings, centre markers, minimap blips
test/
  mock_engine.lua            Stubbed Scar engine for offline testing
  run_tests.lua              112 logic tests
  balance_sim.lua            Boost curves, capture times, zone scaling
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
- **`TD_Config.kings.blueprint`** — not an API name but a *unit name*, and the
  default is a placeholder that will not resolve. See **Kings** above.
- **`Squad_CreateAndSpawnToward`** — king spawning. If it fails no kings appear
  and regicide disables itself; the mode still plays as pure conquest.
- **`Player_GetResource` / `Player_GetResourceGathered`** — gather-rate
  measurement. If both fail, the boost measures zero and never pays out. The
  log line at startup tells you which path it chose.
- **`UI_CreateGroundDecal` / `UI_CreateMinimapBlip`** — zone visuals. Cosmetic;
  safe to leave broken while you get the rest working.
- `World_SetPlayerWin` / `Player_IsAlive` — match end and elimination
  detection. If `Player_IsAlive` fails it assumes everyone is alive, so the
  match will never end.
- `Player_GetStartingPosition` — starting zone placement. If it fails the mode
  spreads starting zones around the map rim instead, which is playable but
  will not match where players actually spawn. The log says when it falls back.
- `Player_GetUIColour` — zone colours fall back to white.
- `World_IsPointOverWater` — water classification. If it fails every zone is
  treated as land, so sea zones may be placed where no ship can be told apart
  from shore, and a player could be given one to start on.
- `World_IsPointOverImpassableTerrain` — zones may land on cliffs. Playable,
  just ugly.
- `Squad_IsAlive` / `Squad_GetHealthPercentage` — king death and the wounded
  warning. A king that cannot be read is treated as alive, so a broken query
  never eliminates someone by accident.

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
lua5.4 test/run_tests.lua      # 112 logic tests
lua5.4 test/balance_sim.lua    # boost curves, capture times, zone scaling
```

The tests stub out the engine entirely, so they verify the *mode's logic* —
capture and contest behaviour, that the boost is a true percentage of what was
gathered, that spending does not zero out the measurement, that conquest
transfers zones to the right player, that every player starts with exactly one
zone and nobody is stranded far from it, that one unit captures in exactly 20
seconds and a 40-unit army cannot do it instantly, that zone count scales with
map and player count while a solo capture still takes 20 seconds on a staggered
66-zone map, that water zones are created
and capturable but never handed out as starting zones, that regicide eliminates
its victim and feeds the conquest path, that the match ends only when one player
remains, and that visuals stay within their opacity budget.

They **cannot** verify that the engine function names are right.

## Tuning

Everything worth changing during playtesting is in `td_config.scar`:

- `boostPerZone` — the headline number. 0.10 is 10% per zone.
- `maxTotalBoost` — ceiling on a runaway leader. `nil` for uncapped.
- `centreZoneWeight` / `edgeZoneWeight` — how hard players are pulled toward
  the middle. Widening the gap makes the mode more aggressive. Weights are
  normalised so the average always lands on `boostPerZone`, whatever you set.
- `startingZonePerPlayer` — whether everyone opens owning a zone.
- `startingZoneFairnessBias` — how hard assignment trades distance for an
  average-weight zone. 0 is strictly nearest.
- `zonesPerPlayer` / `referenceZoneCount` / `maxZoneCount` — how zone count
  scales. `zoneCountOverride` forces a fixed number.
- `zoneRadius` — how much army it takes to cover a zone.
- `zoneScanBudget` — per-tick scan cost ceiling on large maps.
- `maxTotalBoostFraction` — share of the map at which the boost stops growing.
- `captureThreshold` / `captureRatePerSquad` / `captureRateCap` — how long
  zones take to flip and how much a bigger army helps. The three together set
  the 20-second solo capture; change one and that figure drifts, so check
  `balance_sim.lua` after touching any of them.
- `includeWaterZones` — whether the sea is contestable.
- `kings.*` — whether kings exist, what blueprint they use, and whether losing
  one eliminates you.
- `gatherSampleInterval` — how often gather rate is measured. Lower is more
  accurate when the engine lacks cumulative stats; see `td_income.scar`.
- `visuals.*` — everything about how zones are drawn.

After changing anything, run `balance_sim.lua` to see what it did before you
load the game.
