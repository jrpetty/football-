# Maze Runner — NeoForge adventure-map mod (Minecraft 1.21.1)

An authored *Maze Runner* world driven by `maze_config_v2.json` (bundled in the
jar as the single source of truth): a **96×96-chunk** (1536×1536 block) maze of
**unbreakable walls (y61–100)** on a flat floor (y60) under a **barrier ceiling
(y101)**, with the **16×16-chunk Glade** at the centre. Nothing exists outside
the maze but the seven exit pads.

## The loop

- **Custom clock** — 60 real-minute days, 30 real-minute nights (vanilla
  daylight cycle is disabled; the mod drives `dayTime` itself).
- **Dawn (tick 1000):** the four Glade doors grind open.
- **Dusk (tick 12500):** the doors seal shut.
- **Night (tick 18000):** the maze **shifts** — only the ~200 predefined toggle
  points whose state differs between today's and tomorrow's layout animate up
  or down, layer by layer, with grinding stone sounds. The old exit seals, the
  new one opens, and its portal lights up.
- **7 fixed layouts** (all pre-validated solvable) rotate on a schedule that is
  **shuffled once per world from the world seed** — every world plays the same
  7 mazes in a different fixed order, repeating weekly.
- **Escape:** your run starts by itself the moment you first leave the Glade,
  and stops when you reach the **exit portal**. Each death adds 30s to your
  final time and respawns you at the Box; the clock never pauses. Best times
  persist per player — see `/maze leaderboard`.
- **Supply caches:** ~100 chests in dead-end cells with helpful-but-optional
  loot, rerolled at the start of each 7-day cycle.
- Standard survival everywhere — but the walls are bedrock-grade, so the maze
  cannot be mined through or blown up. **Grievers** — the maze's nocturnal
  hunters — stalk the corridors after the doors seal (see below).

**The Glade (v1.1)** — natural rolling terrain (up to +5 blocks, feathered
flat at the walls and doors), a sandy-bedded lake in the southwest, an
oak/birch forest covering about a quarter of the area in the northeast,
flower patches and grasses across the meadow, and the movie-style **Box
elevator** (decorative iron cage, grate and cable) at the exact centre —
which is also world spawn and death respawn (768, 61, 768). Vines and
mangrove-moss climb the Glade-facing walls. Hostile mobs are automatically
purged inside the Glade — it is safe ground; the maze is not.

**v1.14 changes (the corridors have landmarks)** — at minigame scale every
corridor was identical stone, which left runners nothing to recognise and
nothing to map. Now roughly one cell in twelve carries a small feature:

- **Bones** — a runner who didn't make it back.
- **Cobwebs** — something has been sitting here a long time (and they slow you).
- **A lantern** — a light left burning by whoever came before.
- **Rubble** — the Maze grinding itself apart.
- **A cairn** — a torch-topped marker, the tallest and most visible of them.

Each landmark is tinted with its **section's accent colour**, so all eight
eighths of the maze read differently and you can tell roughly where you are by
eye. Two invariants are enforced by tests: a landmark occupies **exactly one of
the four columns** at a cell's open centre — so a corridor can never be blocked
— and never the middle square, where a dead-end supply chest stands. Landmarks
sit on the floor only and never touch walls, so they cannot change whether a
layout is solvable. The Glade, its door ring and the exit cell stay bare.

**v1.13 changes (races are back, alongside personal runs)** — the server-wide
timer returns as a proper **race**, sitting next to the per-player runs rather
than replacing them.

- **`/maze race start`** pulls everyone back to the Box and starts every
  runner's clock together, so the times are directly comparable. The **first
  runner out of the Maze wins**; the winning time is announced and kept as the
  standing **race record** (shown when the next race starts, in `/maze status`
  and at the top of `/maze leaderboard`).
- **`/maze race stop`** cancels a race without recording anything.
- Personal runs, death penalties and the per-player leaderboard all keep
  working exactly as they did — a race just synchronises everyone's start.

**v1.12 changes (it's a game now)** — the minigame loop, death stakes and
advancements.

- **Runs start by themselves.** The moment you first step out of the Glade your
  personal clock starts — no command, no lobby. Reaching the exit portal stops
  it, announces your time, and returns you to the Glade so the world keeps
  playing instead of stranding you on the pad.
- **Per-player timing and a persistent leaderboard.** Every runner has their own
  run and their own best; `/maze leaderboard` (or `/maze top`) lists the ten
  fastest escapes with death counts. `/maze status` now shows your own run.
- **Dying costs you.** Death no longer ends a run — it adds **30 seconds** to
  your final time and is recorded against it. A fast reckless run can lose to a
  slower clean one, which is the point.
- **Six advancements**: *Greenie* (wake up in the Box), *Runner* (leave the
  Glade), *Nobody Survives a Night* (still be out there at dawn), *Griever
  Killer*, *The Cure* (burn out the Changing with a serum) and *Free* (escape).

Note: `/maze start` and `/maze stop` now act on **your own** run — restart it
from zero, or abandon it without recording — rather than a single server-wide
timer.

**v1.11 changes (the Grievers have a voice)** — you now hear one long before
you see it, and what you hear tells you how much trouble you are in:

- **Distant** (up to 48 blocks) — grinding machinery somewhere in the corridors.
- **Stalking** (within 28) — heavy metallic footfalls, positional, so you can
  take a bearing and run the other way.
- **Hunting** (within 14, or 28 once it has your scent) — your own heartbeat.

A Griever **roars once when it first locks on**, so you know the moment you've
been found, and again on every sting. Its whole voice is re-pitched well below
vanilla — deep ravager growls, iron-golem footfalls — so it reads as something
big, slow and mechanical rather than a spider. When the doors seal at dusk, a
distant roar rolls across the Glade.

**v1.10 changes (the maze can no longer be cheated)** —

- **Fixed a game-breaking exploit: you could climb out and walk over the maze.**
  The ivy hung from y76–78 on walls topping out at y78, and vines are
  climbable; the Glade wall coat stacked *mangrove leaves* (full collision) just
  as high. A runner could climb the greenery onto the wall crest and stroll
  across the roof straight to the exit, skipping the maze entirely. Greenery now
  stops **4 blocks below the crest** — too far to jump, and above the build
  limit so it can't be pillared to either. A runtime backstop drops anyone who
  still ends up above the walls back into the corridor below. Pinned by tests.
- **The Griever Serum is now a real item that actually cures the Changing** —
  drink it to purge the venom (poison, weakness, nausea) and get a few seconds
  of regeneration and resistance. Grievers drop one; caches stock it rarely.
- **Performance:** the nightly Griever housekeeping used to sweep a
  576×576-block box every second. It now uses the level's entity type index.

**v1.9 changes (correctness + a real test suite)** — a full back-to-front audit
with automated tests that now run on **every build**.

- **Fixed: 104 of the 105 supply caches never appeared.** Since the v1.6
  downscale a cell (6 blocks) is no longer a chunk (16 blocks), but the runtime
  was still looking chests up by *cell* index while snapping *chunks* — so a
  chest was attempted for the wrong chunk and written at a block position in a
  different, often unloaded, chunk. Chest sites are now indexed by the chunk
  that actually contains them, with a regression test pinning the mapping.
- **The clock is now a pure, tested unit** (`MazeClock`): the day/night rates,
  the five daily events and the "skipping time fires everything you pass"
  behaviour are all covered by tests, including the exact time-skip case that
  broke in v1.4.
- **Tree geometry is a pure, tested unit** (`TreeShape`), shared by the
  generator instead of duplicated — so crown sizes can't silently drift out of
  the wall clamp.
- **40 unit tests** (JUnit 5) cover the maze graph and solvability, chunk
  indexing, the clock, the Glade terrain, the lake, and the forest. `./gradlew
  build` runs them, and CI fails the build if the suite doesn't run or a single
  test fails.

**v1.8 changes (trees)** — the Glade forest is now **plain base-game trees at a
natural mix of sizes**. Dynamic Trees support has been removed entirely: every
tree is a vanilla oak, birch or dark oak generated **full-grown at worldgen** as
an ellipsoidal crown on a log trunk — **birches tall and slim, dark oaks broad
and low, oaks in between** — rolled small / medium / large per tree with a gentle
regional lean so the woods have real height variation instead of one uniform
stand. No mod dependency, no sapling pop-in.

**v1.7 changes (Grievers)** — the maze now has its signature threat. When the
Glade doors seal at dusk, **Grievers** begin to spawn in the corridors around
each runner and hunt them through the night. They are big, fast, tough
spider-kin (60 HP, quick, wall-climbing) whose sting inflicts **the "Changing"**
— poison, weakness and nausea — and they **glow** so you can read them coming
around a corner. Kill one and it drops a **Griever Serum**. They only roam at
night: any that survive to daybreak **retreat into the walls** when the doors
open, and none can enter the safe Glade. The nightly pressure **escalates each
week** (one more Griever per runner per completed 7-day cycle, up to a cap). New
debug command **`/maze griever`** spawns one near you, and **`/maze status`**
now reports how many are loaded.

**v1.6 changes (minigame scale)** — everything is downscaled to a **6-block
cell (~63% smaller footprint)**: the map is now 576×576 blocks (was 1536), the
Glade 96×96, walls 18 tall, hills to +3, lake depth 4. The same authored maze
topology, fixed exit, per-door divergence, day/night cycle and features are all
preserved. Two scale tradeoffs: corridors are 2 wide so wall relief is
**recessed-niches only** (outward pushes would seal a 2-wide corridor), and the
big **ruin/dungeon structures are off** (they don't fit tiny cells) — dead-end
supply chests remain. Toggle to a larger scale by raising `CELL_SIZE` in the
config if you want the full in/out relief and ruins back.

**v1.5 changes** — the permanent **ring corridor around the Glade is cut at its
four corners**, so each door now leads into its own separate part of the maze
instead of all four sharing one loop (some doors dead-end or miss the exit on a
given day — pick wisely; all 7 days remain solvable). The **forest is a natural
mix** of oak/birch/dark-oak/apple rather than single-species stands. **Fixed the
day/night skip**: jumping time now fires every event it passes, so doors
actually seal and walls actually move. New creative controls: **`/maze night`**
(skip to nightfall, doors seal), **`/maze shift`** (deep night — seal doors and
move the walls now), and **`/maze morning`** (= skip/endday: seal → reshape →
dawn → open).

**v1.4 changes** — the **exit portal is now fixed** in one place (the south
exit); every day only the *walls* reshape, so the route you navigate to that
one exit changes (routes range ~150–350 cells across the week) while the goal
never moves. All seven daily layouts are verified solvable to it. The
**Glade-enclosing walls now carry the same in/out panel relief** as the maze (protruding into the Glade edge and recessing into niches,
alternating every 8 blocks). Wall greenery now uses **vanilla vines** instead of
the custom ivy block. When **Dynamic Trees** is installed, the Glade forest's DT
saplings are **bonemealed to full maturity** on load (via the vanilla
bonemealable interface — no DT API needed), so the woods are grown at first
sight; without DT, built-in trees are used. (Reminder, unchanged and verified:
Glade doors open at dawn and seal at dusk, and the day→layout schedule repeats
weekly — week 1 day 1 == week 2 day 1 — with paths opening/closing each night.)

**v1.3 changes** — the walls that enclose the Glade are now **thickly overgrown**
(mangrove-leaf bushes and cascading vines, with natural gaps) and carry the same
panel relief. **Walls now rise to y110** while a **build cap keeps players at
y86** (survival; creative bypasses). The **lake is 5 blocks deeper** — the
**bedrock was lowered** to make room — and the Glade **hills now reach +8** in
the tallest spots (still smooth). Forest trunk sites use **Dynamic Trees**
species (apple-oak/oak/dark-oak/birch) when that mod is installed, falling back
to built-in trees otherwise. World spawn sits inside the Box elevator.

**v1.2 changes** — the world is now a custom dimension with a **Y86 build
limit and no barrier ceiling** (open sky above the walls). Maze walls carry
**movie-panel relief** (each 8-block panel pushed 1 in or out, alternating),
with **bulked entry posts** framing each Glade door. The **ivy** is rebuilt:
cascading, clustered, biome-tinted strands that hang from the top and taper —
no more dark stripes (the mangrove moss is gone). The **lake** is deeper and
irregular with a mixed **sand/clay/gravel/dirt** bed. Glade **elevation** is
fuller, and **world spawn is now inside the Box elevator**. The Glade forest
uses **oak, birch, dark-oak and apple-oak** stands (built-in; see the Dynamic
Trees note below). New: **`/maze endday`** (alias of `/maze skip`) to fast-
forward a day for creative testing.

**The maze (v1.1 base)** — walls wear a weathered palette from dark to light
(polished tuff → tuff → polished andesite → mossy stone bricks → stone
bricks → andesite) with wandering band boundaries, all still bedrock-grade
unbreakable. Non-spreading ivy and moss clumps break up the faces (movable
segments stay bare). **Fog** rolls through the corridors for the first ten
real minutes of each morning and for ten minutes in the dead of night —
exactly while the walls shift. Ten **plaza clearings** interrupt the
corridors: ruined shelters with campfires, pillar courts, collapsed
watchtowers, and surface ruins hiding **underground dungeon rooms** with a
zombie spawner and extra loot. Plazas only remove walls, so every layout
stays solvable.

Quality-of-life on top of the spec:

- A **boss-bar clock**: "Day N — doors seal in 41:23" by day, "Night N — the
  Maze shifts. Dawn in 12:41" by night.
- A red **"doors seal soon"** warning shortly before dusk.
- A persistent **best-time world record**, announced when beaten.
- `/maze tp` — teleport an op to the day's active exit portal for testing.

## Install & play

1. Install [NeoForge](https://neoforged.net/) for **Minecraft 1.21.1** and drop
   `mazerunner-1.0.0.jar` into `mods/`.
2. Create a new world → World Type → **"Maze Runner"**.
3. You spawn at the centre of the empty Glade. Doors open shortly after the
   world starts.

## Commands (op level 2)

| Command | Effect |
| --- | --- |
| `/maze start` / `/maze stop` | Restart / abandon your own run |
| `/maze leaderboard` / `top` | The ten fastest escapes + the race record |
| `/maze race start` / `stop` | Start a synchronised race for everyone / cancel it |
| `/maze status` | Day, layout, doors, clock, timer, week schedule |
| `/maze validate <1-7>` | BFS-verify a layout is solvable (debug) |
| `/maze section` | Which of the 8 maze sections you're standing in |
| `/maze tp` | Teleport to the day's active exit portal (debug) |
| `/maze skip` / `endday` / `morning` | Jump to next dawn — fires dusk/shift/portal swap on the way (debug) |
| `/maze night` / `shift` | Skip to nightfall (doors seal) / deep night (walls move now) |
| `/maze griever` | Spawn a Griever in a corridor near you (debug) |

## Build from source

Java 21. First build needs `maven.neoforged.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`.

```bash
cd mazerunner-neoforge
./gradlew build          # compiles, runs the unit tests → build/libs/mazerunner-*.jar
./gradlew test           # unit tests only (no Minecraft needed)
./gradlew runClient      # dev client
```

The test suite (`src/test/java`) covers the pure-logic half of the mod — the
maze graph and per-day solvability, chunk indexing, the day/night clock, and
the Glade terrain and forest — so it runs in seconds without bootstrapping
Minecraft. CI runs it on every push and fails the build if the suite doesn't
run or any test fails.

Maze dataset: regenerate with `generate_maze.py` (attached to the project
spec) and replace `src/main/resources/data/mazerunner/maze/maze_config_v2.json`.

### Trees

The Glade forest is plain base-game oak, birch and dark oak, generated
full-grown at worldgen at a natural mix of small/medium/large sizes (see the
v1.8 note above). There is no mod dependency and nothing to install for the
forest — it populates itself. Tune the look in `GladeTerrain.treeSize` /
`trunkHeight` and `TreePlacer` (crown radii per species and size).
