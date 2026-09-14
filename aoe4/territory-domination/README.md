# Territory Domination

An Age of Empires IV game mode. The map is a **grid of square zones**, tiling
it corner to corner — land and sea alike, with no neutral ground between.
Walk one unit into a zone for 20 seconds and it is yours. **Every zone you
hold adds 10% to your gather rate.**

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

- **Square cells tiling the whole map**, sized so every player opens with a
  base tile, a home ring they can take uncontested, and exactly one neutral
  tile between their ring and the next player's.
- **No neutral ground.** Cells share every edge, so every unit on the map is
  always inside exactly one zone and the whole map is always worth something
  to somebody.
- **Every player starts owning the zone nearest their base**, already fully
  captured, so everyone opens on +10% rather than scrambling from zero.
- **Standing in a zone captures it.** One unit takes exactly 20 seconds, two
  take 10, three take 6.7.
- **A held tile must be cleared first.** An owned tile cannot be taken while
  its owner has anything still standing in it — every unit and building of
  theirs inside the tile has to be destroyed before capture even begins.
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

### The opening layout

Cell size is not chosen — it is **derived from where players actually spawn**,
so the opening reads the same on every map and at every player count. Along the
line between two neighbours:

```
[ base ][ home ring ][ neutral ][ their ring ][ their base ]
```

You own your base tile and can take the ring around it uncontested, because the
nearest rival ring is a full tile away. That puts bases **four tiles apart**:

```
basesApart = 2 x homeRingRadius + neutralGapTiles + 1 = 2 + 1 + 1 = 4
```

so `cellSize = (closest pair of spawns) / 4`.

**The distance that matters is Chebyshev, not straight-line.** A home ring is
the eight tiles touching your base — a king-move radius of 1. Two bases four
tiles apart in *straight-line* distance but placed diagonally are only 2.83
apart in king moves, which leaves 0.83 of a tile between their rings: they
touch, and the neutral tile does not exist. Measuring in king moves gives a
clean one-tile gap in every direction.

| Players | Map | Grid | Tiles | Cell | Base sep | Neutral band | Per player |
|---|---|---|---|---|---|---|---|
| 2 | 400 | 6x6 | 36 | 66.7 | 5 | 2 | 18.0 |
| 3 | 480 | 8x8 | 64 | 60.0 | 4 | 1 | 21.3 |
| 4 | 560 | 12x12 | 144 | 46.7 | 4 | 1 | 36.0 |
| 6 | 690 | 14x14 | 196 | 49.3 | 4 | 1 | 32.7 |
| 8 | 800 | 17x17 | 289 | 47.1 | 4 | 1 | 36.1 |

Column counts round **up**, so cells come out no larger than ideal — rounding
down would make them bigger and drop the separation below four, collapsing the
gap. The cost is that a map which does not divide evenly can end up with a
slightly wider band than one tile, as the 1v1 row shows. It is never narrower.

Each player's base is **the cell their Town Centre physically stands in**, not
merely a nearby one — the fairness bias used elsewhere is skipped here, since
nudging someone onto a neighbouring tile would pull their home ring off centre
and collapse the neutral gap on one side.

Where spawns are unknown, or where the grid this implies would exceed
`maxZoneCount`, the mode falls back to sizing by map area and player count and
**says so in the log** — the opening layout is not silently abandoned.

### Scale, and what 10% per tile now means

Deriving cells from spawn spacing means tile counts vary a lot: 36 on a 1v1,
289 on an 8-player map. At a flat 10% per tile:

| Players | Tiles | Home ring | Fair share | Whole map |
|---|---|---|---|---|
| 2 | 36 | +90% | +180% | +360% |
| 4 | 144 | +90% | +360% | +1440% |
| 8 | 289 | +90% | +361% | +2890% |

**The home ring is always +90%** — it is always nine tiles, at every player
count. That part is stable and is the uncontested opening economy.

Everything past it is not. A fair share of an 8-player map is four times what
it is in a 1v1, and the map as a whole carries eight times the boost. The rule
is symmetric, so nobody is disadvantaged, but 8-player games run on a much
richer economy than 1v1s.

`boostAutoScale = true` holds the map's total constant at `boostAtFullMap`
instead, trading the literal 10% for a consistent economy across player counts.
It is **off by default**, because 10% per tile was a deliberate choice and
turning this on silently would change what that number means.

### Frontier-first scanning

289 tiles against 8 players is over 2000 spatial queries per full sweep — at a
budget of 24 a tick, twelve seconds before a new arrival is noticed.

A tile whose neighbours all share its owner cannot change hands until one of
those neighbours does, because units have to walk in across the border. So the
budget goes to the **frontier** — tiles bordering somebody else, plus anything
unowned or contested — and interior tiles are swept once every fourth pass. On
a settled 17x17 map that is the difference between noticing a raid in twelve
seconds and noticing it in three.

This does not change capture speed. Progress is credited by elapsed time since
that tile was last examined, so a tile looked at every third tick gains three
ticks' worth. An unowned tile is always frontier, so a capture in open ground
is never slowed by the interior budget.

**The grid shape comes from the map's aspect ratio, not the target count.**
Choosing cols and rows by count alone and squaring the cells afterwards left
210 units of a 560-wide map outside the grid entirely, which defeats the point
of tiling it. Cells now divide the usable map exactly.

Squareness is weighted to win near-ties decisively. On a square map a 5x4 grid
hits the target count exactly while 4x4 is two zones short, and the two scored
within 1e-15 of each other — so which one won was down to floating-point
noise. `squareCells = false` hits the target count exactly and accepts oblong
cells.

**Impassable cells stay in the grid.** Dropping cliff cells would punch holes
in a grid whose whole point is to tile the map, so they are kept and simply
stay neutral because nobody can stand in them.

**The square cell test is not the engine's circular one.** The engine's
spatial query is a radius, so the adapter asks for everything within a cell's
circumradius and then keeps only what is really inside the rectangle. Without
that filter a unit in a neighbour's corner would count for both cells — and
with cells sharing every edge, that is constant rather than rare. If the
engine will not let us walk the group, it falls back to the circular count and
logs it once; captures then bleed slightly across corners.

Two things also had to change to let zone count scale:

**The boost cap.** It used to be a fixed +200%, which was fine at 18 zones
where it could never bind. On a 64-zone map it would bind at a 30% map share,
making every zone past the twentieth worthless and breaking the mode in 4v4.
It is now a fraction of the *whole map's* boost (default 0.75), so it means
the same thing at every size.

**Scan cost.** Every zone is checked against every player, so a 64-zone
8-player map would be ~510 spatial queries per second. Zones are now scanned
round-robin against a per-tick budget (default 24). This does **not** change
capture speed: progress is credited by elapsed time since that zone was last
examined, so a zone looked at every third tick gains three ticks' worth. The
only cost is that a newly arrived enemy takes up to `zones / budget` ticks to
be noticed. There is a test asserting a solo capture still takes 20 seconds on
a 64-zone map.

### Taking ground vs taking a position

Empty territory changes hands by walking into it. A **defended tile does not**:
while its owner has anything still standing inside it, capture progress does
not start at all, however many attackers are stood there. Two hundred units
will not take a tile from one surviving spearman until that spearman is dead.

Buildings count. A building with no garrison cannot fight, but it holds the
ground it stands on, so **one surviving structure holds a tile indefinitely**.
That is what makes a base a base rather than just another tile.

A blocked attacker keeps their progress rather than losing it — they are still
stood there, they just are not winning yet. Set `defenceResetsProgress = true`
to make a failed assault start over instead.

Neutral tiles are unaffected: there is no owner to clear.

A tile being held against an attacker renders at the loudest opacity on the
map (0.85, above ordinary contest at 0.55), because the attacker needs to see
that they are making no progress rather than assume the capture bar is just
slow.

Both halves are switchable: `mustClearDefendersToCapture = false` restores
plain contest rules, and `buildingsHoldTiles = false` lets an ungarrisoned
building fall with the ground it stands on.

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
| 2 | 9.6% – 9.6% | 0% | 51 units |
| 4 | 9.3% – 9.3% | 0% | 43 units |
| 8 | 10.2% – 10.8% | 6% | 78 units |

A symmetric square grid gives symmetric starts, so the fairness spread that
used to reach 35% in an 8-player FFA is now 6%.

Set `startingZonePerPlayer = false` to start everyone from zero
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
  td_visuals.scar            Cell outlines, centre squares, minimap tiles
  td_tally.scar              Per-player territory counts, named by colour
test/
  mock_engine.lua            Stubbed Scar engine for offline testing
  run_tests.lua              192 logic tests
  balance_sim.lua            Opening layout, economy, boost curves, capture times
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
- **`SGroup_GetSpawnedSquadAt`** — lets the square cell test filter a circular
  query down to the actual rectangle. Without it captures bleed across cell
  corners; the log says once if it fell back.
- **`Player_GetEntitiesNearPoint`** / `EGroup_GetSpawnedEntityAt` — building
  presence, which is what makes a held tile hold. If these fail, buildings
  stop anchoring tiles and a base can be captured out from under its own Town
  Centre.
- **`UI_CreateGroundDecalRect`** — square cell outlines and centre squares.
  Falls back to whatever decal call exists, so cells may draw as circles.
- **`UI_CreateMinimapRect`** — filled minimap cells. Falls back to a blip,
  which still marks each cell but makes territory much harder to count.
- **`UI_SetPanelText`** — the on-screen tally. Falls back to rate-limited chat
  messages; the log says once which path it took.
- `Player_GetUIColourName` — colour names in readouts. Falls back to this
  mode's own slot palette (Blue, Red, Green, Yellow, Purple, Teal, Orange,
  Pink), which is correct unless a player picked a non-default colour.
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
lua5.4 test/run_tests.lua      # 192 logic tests
lua5.4 test/balance_sim.lua    # boost curves, capture times, zone scaling
```

The tests stub out the engine entirely, so they verify the *mode's logic* —
capture and contest behaviour, that the boost is a true percentage of what was
gathered, that spending does not zero out the measurement, that conquest
transfers zones to the right player, that every player starts with exactly one
zone and nobody is stranded far from it, that one unit captures in exactly 20
seconds and a 40-unit army cannot do it instantly, that cells tile with no gaps or overlaps and no point
falls in two zones, that a unit in a neighbouring cell's corner does not count
toward this one, that every player's base is the cell they spawned
in, that no two bases sit closer than four tiles and home rings never touch,
that every ring tile is nearer its own base than any rival's, that a neutral
tile really exists between the closest pair, that a defended tile cannot be
taken by any number of attackers until its defenders are destroyed and that a
lone building holds it, that an unaffordably fine grid is
clamped and flagged rather than silently abandoned, that a solo capture still
takes about 20 seconds on a staggered grid and interior tiles are still swept, that water zones are created
and capturable but never handed out as starting zones, that regicide eliminates
its victim and feeds the conquest path, that the tally counts every player's tiles and
names them by colour, that it redraws only when counts move and rate-limits its
chat fallback, that the match ends only when one player remains, and that no
visual falls back to a circle.

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
- `homeRingRadius` / `neutralGapTiles` — the opening layout. Bases-apart is
  derived from these, never set directly.
- `deriveGridFromSpawns` — size cells from spawn spacing, or ignore the opening
  layout and size by map area instead.
- `boostAutoScale` / `boostAtFullMap` — hold the map's total boost constant
  instead of the per-tile figure.
- `interiorScanEveryNth` — how much cheaper interior tiles are than frontier
  ones.
- `squareCells` — prefer square cells over hitting the target count exactly.
- `mapEdgeMargin` — fraction of the map left outside the grid; 0 tiles it all.
- `zoneScanBudget` — per-tick scan cost ceiling on large maps.
- `maxTotalBoostFraction` — share of the map at which the boost stops growing.
- `mustClearDefendersToCapture` / `buildingsHoldTiles` /
  `defenceResetsProgress` — whether a held tile must be cleared, whether
  buildings count, and whether a failed assault loses its progress.
- `captureThreshold` / `captureRatePerSquad` / `captureRateCap` — how long
  zones take to flip and how much a bigger army helps. The three together set
  the 20-second solo capture; change one and that figure drifts, so check
  `balance_sim.lua` after touching any of them.
- `includeWaterZones` — whether the sea is contestable.
- `kings.*` — whether kings exist, what blueprint they use, and whether losing
  one eliminates you.
- `gatherSampleInterval` — how often gather rate is measured. Lower is more
  accurate when the engine lacks cumulative stats; see `td_income.scar`.
- `visuals.*` — everything about how cells are drawn, including the minimap
  fill strengths that make territory countable.
- `tally.*` — the per-player tile count: refresh rate, whether to show share
  and boost, and the chat fallback's rate limit.

After changing anything, run `balance_sim.lua` to see what it did before you
load the game.
