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
- **Escape:** `/maze start` starts a single server-wide timer; the first player
  to run into the **active exit portal** stops it and the time is announced.
  Deaths respawn at the Box in the Glade; the timer never pauses.
- **Supply caches:** ~100 chests in dead-end cells with helpful-but-optional
  loot, rerolled at the start of each 7-day cycle.
- Standard survival everywhere — but the walls are bedrock-grade, so the maze
  cannot be mined through or blown up. Hostile mobs roam the corridors at
  night. (Custom Grievers are a later phase.)

**The Glade (v1.1)** — natural rolling terrain (up to +5 blocks, feathered
flat at the walls and doors), a sandy-bedded lake in the southwest, an
oak/birch forest covering about a quarter of the area in the northeast,
flower patches and grasses across the meadow, and the movie-style **Box
elevator** (decorative iron cage, grate and cable) at the exact centre —
which is also world spawn and death respawn (768, 61, 768). Vines and
mangrove-moss climb the Glade-facing walls. Hostile mobs are automatically
purged inside the Glade — it is safe ground; the maze is not.

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
| `/maze start` / `/maze stop` | Start / manually stop the escape timer |
| `/maze status` | Day, layout, doors, clock, timer, week schedule |
| `/maze validate <1-7>` | BFS-verify a layout is solvable (debug) |
| `/maze section` | Which of the 8 maze sections you're standing in |
| `/maze tp` | Teleport to the day's active exit portal (debug) |
| `/maze skip` | Jump to next dawn — fires dusk/shift/portal swap on the way (debug) |

## Build from source

Java 21. First build needs `maven.neoforged.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`.

```bash
cd mazerunner-neoforge
./gradlew build          # → build/libs/mazerunner-1.0.0.jar
./gradlew runClient      # dev client
```

Maze dataset: regenerate with `generate_maze.py` (attached to the project
spec) and replace `src/main/resources/data/mazerunner/maze/maze_config_v2.json`.

### Dynamic Trees

The Glade forest ships with built-in oak/birch/dark-oak/apple-oak trees so it
always populates. True Dynamic Trees integration (growing DT species) requires
compiling against the Dynamic Trees API — tell me the DT version you run and
I'll wire it as a compile-time dependency so the forest uses real DT trees.
