# Maze Runner — handoff

Everything a new session (or a new person) needs to take this over and put it
on a server. Current version **1.15.0**, Minecraft **1.21.1**, NeoForge
**21.1.x**.

---

## 1. What this is

A **NeoForge mod**, not a datapack and not a pasted schematic. It *generates*
an authored *Maze Runner* world from a bundled dataset and then drives it at
runtime — the walls physically move each night, doors open and seal on a clock,
Grievers hunt after dark, and runs are timed.

Two consequences worth understanding before planning anything:

- **It replaces the overworld.** The world preset points
  `minecraft:overworld` at a custom chunk generator. There is no separate
  "maze dimension" to travel to — the maze *is* the overworld, and everything
  outside the maze square is void.
- **The map is generated, not built.** There is no schematic to import. To
  change the maze's shape you change the dataset or the generator code, not
  blocks in a world file.

---

## 2. Where everything lives

| What | Where |
| --- | --- |
| Repo | `jrpetty/football-` |
| Branch | `claude/maze-runner-gamemode-map-4gzuak` |
| Mod source | `mazerunner-neoforge/` |
| **Built jar** | `mazerunner-neoforge/dist/mazerunner-1.15.0.jar` |
| Maze dataset (source of truth) | `src/main/resources/data/mazerunner/maze/maze_config_v2.json` |
| CI that builds the jar | `.github/workflows/build-mazerunner.yml` |

**The jar is committed to the repo.** Every push to that branch triggers CI,
which compiles, runs the tests, verifies the jar's contents and commits the
built jar back to `dist/`. You do not need a working Gradle setup to get a jar.

> `maze_config_v2.json` is the **source of truth** for the maze's topology —
> corridors, the 200 toggle points, the 7 layouts, the exits. It was authored
> once and must not be regenerated casually; read and consume it.

---

## 3. Putting it on a server

1. Install **NeoForge 21.1.x** for **Minecraft 1.21.1** on the server.
2. Drop `mazerunner-1.15.0.jar` into `mods/`. The same jar goes in each
   **client's** `mods/` folder — it registers blocks, an entity and a renderer,
   so clients need it too.
3. **This is the step people miss.** A dedicated server has no world-type
   picker, so set the preset in `server.properties` **before first start**:

   ```properties
   level-type=mazerunner:maze_runner
   ```

   Delete or move any existing world folder first — the generator is baked into
   the world on creation and cannot be switched afterwards.

4. Start the server. Spawn is inside the Box elevator at **(287, 62, 287)**.

In single-player, instead pick **World Type → "Maze Runner"** on the world
creation screen.

---

## 4. The world, in numbers

| | |
| --- | --- |
| Map | 576 × 576 blocks (96 × 96 cells of 6 blocks) |
| Glade | 96 × 96 blocks, cells 40–55, blocks 240–335 |
| Spawn / respawn | (287, 62, 287), inside the Box |
| Corridor floor | y60 · walls y61–78 · **build limit y72** |
| Bedrock | y53 · lake bottoms above that |
| Dimension | height 80, min_y 0, logical height 72 |
| Layouts | 7, one per day, shuffled once per world from the seed, repeating weekly |
| Toggle points | 200 movable wall segments |
| Exit | **fixed** (`exit_3`, south). Only the walls change, never the goal |
| Day length | 90 real minutes (60 day / 30 night) |

Daily clock (day-ticks): `1000` doors open · `11500` dusk warning ·
`12500` doors seal · `18000` the maze reshapes · `24000` dawn.

---

## 5. Commands (`/maze …`, op level 2)

| Command | Effect |
| --- | --- |
| `status` | Day, layout, clock, doors, Grievers loaded, your run, race state |
| `leaderboard` / `top` | Ten fastest escapes + the race record |
| `race start` / `race stop` | Synchronised race for everyone / cancel |
| `start` / `stop` | Restart / abandon **your own** run |
| `section` | Which of the 8 compass sections you're in |
| `tp` | Teleport to the exit portal (debug) |
| `griever` | Spawn a Griever near you (debug) |
| `night` / `shift` / `morning` (= `skip`, `endday`) | Jump the clock; fires every event crossed |
| `validate <1-7>` | BFS-check a layout is solvable |

---

## 6. Tuning it (`serverconfig/mazerunner-server.toml`, in the world folder)

```toml
[grievers]
enabled = true            # switch them off entirely
baseCapPerPlayer = 2      # per runner, week 1
maxCapPerPlayer = 7       # ceiling (grows +1 per week)
health = 60.0             # vanilla spider is 16
speed = 0.33              # vanilla spider is 0.3; below ~0.25 a runner can outrun one
attackDamage = 7.0

[run]
deathPenaltySeconds = 30  # 0 disables the penalty
showBriefing = true       # one-time rules message for newcomers
```

Griever stats apply **per spawn**, so edits take effect the next night without
a restart. Every value falls back to its default if the file is missing or
malformed.

---

## 7. Architecture, in one minute

```
config/   pure Java, no Minecraft imports  →  unit-tested (73 tests)
gen/      chunk generator + builders       →  needs Minecraft
*.java    runtime, commands, entity, items →  needs Minecraft
```

The split is deliberate: anything that can be reasoned about without a running
game (maze graph, solvability, geometry, the clock, tree and crown shapes,
landmark placement, scoring, the Griever fear curve) lives in `config/` and is
covered by JUnit. `./gradlew build` runs those tests and **CI fails the build
if the suite doesn't run or any test fails**.

Key classes: `MazeConfigData` (dataset + geometry), `MazeChunkGenerator`,
`MazeRuntime` (the whole live loop), `MazeWorldState` (persistent state),
`MazeClock`, `RunScoring`, `MazeLandmarks`, `GrieverAudio`, `MazeConfig`.

---

## 8. What is verified, and what is not — read this

**Verified:** 73 unit tests (all 7 layouts solvable to the fixed exit, chunk
indexing, clock and event ordering, terrain and lake bounds, tree crowns under
the wall clamp, landmark safety invariants, run scoring). CI compiles the whole
mod against real NeoForge and checks the jar's contents on every push.

**Not verified — nobody has ever launched this in Minecraft.** The build
environment has no game client, so everything below is reasoned, not observed:

- **Griever balance is a guess.** They're faster than a sprinting player, 60 HP,
  7 damage, scaling to 7 per runner. Faithful to the films, possibly unfair.
  This is the single most likely thing to need changing — hence the config file.
- **Visuals are unseen**: landmark density and colours, wall relief, the
  forest, lake edges, ivy.
- **Feel is unseen**: whether the Griever audio cues read correctly, whether a
  90-minute day is too long, whether 2-wide corridors are claustrophobic in a
  good way or a bad one.
- Multiplayer has never been exercised with more than zero players.

A related limitation: the Minecraft-dependent classes **cannot be compiled
locally** in that environment, so CI is the only compiler for them and a typo
there costs a full CI cycle to find. The test suite covers only `config/`.

---

## 9. Known gaps / next steps

- **Navigation & mapping** — the one Runner fantasy still missing. Nothing
  rewards charting the maze; a Runner's log, a section map, or making the loot
  compass point at the exit would close it.
- **Ruins/plazas are disabled** (`MazeStructures.PLAZA_COUNT = 0`) — they
  didn't fit 6-block cells after the v1.6 downscale. Landmarks (v1.14) replaced
  them at small scale. `RuinBuilder` is dead code kept for a larger scale.
- **Scale is a single constant.** `MazeConfigData.CELL_SIZE` (6) drives
  everything; raising it re-enables full in/out wall relief and would make room
  for ruins again, at the cost of a much bigger map.
- The Glade was declared finished by the owner — **do not rebuild it.**

---

## 10. Version history (short)

`1.6` downscale to a 6-block cell minigame · `1.7` Grievers · `1.8` vanilla
trees at mixed sizes · `1.9` fixed 104/105 broken supply caches, added the test
suite · `1.10` fixed a maze-bypass climbing exploit, real Griever Serum ·
`1.11` Griever audio · `1.12` per-player runs, death penalty, advancements ·
`1.13` synchronised races · `1.14` corridor landmarks + section colours ·
`1.15` server config + newcomer briefing.

Full detail per version is in `README.md`.
