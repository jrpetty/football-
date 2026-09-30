# Village Folk

A settlement that runs itself. Nobody hires them, points them at ground, or gives
them a trade: they choose a trade the village is short of, stake ground for it,
work it, stash what they make, eat, sleep, build the village up and — if they are
fed and in work — raise children.

Everything is obtainable in **survival**.

## Getting a village

| How | What you do |
|---|---|
| **Village Folk Spawner** (recommended) | Craft it — 8 bread round a gold ingot — and *place* it. The first one founds a village on the spot and leaves the founding stores in a chest where it stood; each one placed after that, within reach, adds a settler. |
| **Village Charter** | Right-click the ground. Same result as the spawner; craftable with paper, bread, a gold ingot, seeds and a chest. |
| **/village spawn [1-100]** | Stands folk up two blocks ahead of you. `spawnat <x> <z> [n]` for the console. |
| **The world** | Villages generate as you explore (config `naturalVillages`), in groups of three to five. |
| **Vanilla villagers** | Turned into folk as you meet them (config `replaceVillagers`). Trading with them stops working; wandering traders are untouched. |

## What they do

* **Trades** — at ten folk: four farmers, three miners, two woodcutters, one
  smelter. More trades open as the village grows: a watch, a carrier, a
  storekeeper, a rancher, a fisher.
* **Ground** — each trade claims its own plot (farms by water, woods, hillsides
  to dig) and works it. Plots are never shared.
* **Stores** — every chest and furnace a folk puts down is named **Village
  Store**. Folk use *only* containers with that name, so a village founded next
  to your base leaves your chests alone. To let them use a chest of yours,
  rename it *Village Store* in an anvil. A sign on a chest still hides it from
  everybody.
* **Food** — wheat is baked into bread by whoever is idle (including everybody
  at dusk, indoors). A folk short of rations fetches some from the stores.
* **Building** — one project at a time, one lead builder per project, on a lot
  chosen once on the village's own grid: storage, shelter, houses, then (as the
  village comes of age) a wall, a smeltery, a workshop, a watchtower, a
  lighthouse.
* **Days** — the day shift works the day, then goes home; the watch keeps the
  night. Nobody builds, mines or moves house after dark. Sleeping in a bed skips
  the night for everybody, wherever you are.
* **Ages** — Wood → Stone → Iron → Diamond → Nether, each with its own list of
  what the village needs. A village that reaches a new age says so, once, in
  chat. Folk themselves never speak.
* **Growth** — two folk who are fed and in work, standing together, have a
  chance of raising a child, paced across the whole village. Cap: 100 by
  default, 500 at most (`villageGrowthCap`).

Right-click a folk to see what it carries and what it is doing. You can look;
only its owner (nobody, for folk) can rearrange the pack.

## Commands

* `/village status` — age, headcount, trades, what the stores hold, what has
  been built, what the village is short of. Works from the console.
* `/village folk` — one line per folk: trade, ground, status, job, what is
  missing, what it carries (`pack=`), what it last tried at the essentials
  gate (`gate=`), and a trail of the jobs it has run.

## Config (`config/mc_assistant-common.toml`, section `[villages]`)

`naturalVillages`, `villageSpacing`, `villageMinFolk`, `villageMaxFolk`,
`villageBreeding`, `villageGrowthCap`, `villageLoadedChunks`, `replaceVillagers`,
`protectTradedVillagers`.

## How this is tested

Compiling proves nothing about a system this large, so the village is *run*.
Every push to CI:

* boots a real headless server and runs the game tests in
  `src/gametest/java` — recipes load, the spawner block, the charter and the
  commands work, a vanilla villager is swapped for a folk, and twelve folk are
  left alone for **three game days** on ground of the test's own making;
* starts a real dedicated server with vanilla world generation, settles a
  village in a chosen biome over RCON (`tools/realworld/soak.py`), lets three
  game days pass at full speed, and reports;
* checks a generated vanilla village's villagers are converted without
  freezing the server.

The reports are published to the `village-test-latest` release: `vt-report.txt`
and `real-<scenario>.txt`. Each dashboard line says what one folk is doing and
why it is not doing more.
