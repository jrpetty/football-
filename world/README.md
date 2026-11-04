# 🌍 Genesis — an AI civilisation

A living world where every inhabitant can be driven by **its own Claude mind**.
People start with nothing: a few small bands, bare hands and an Earth-like land.
They gather, hunt, farm, build, research, court, raise children, quarrel, split
into new tribes, pass laws and found beliefs. Over generations they climb from
the Stone Age toward railways, computers and, eventually, a rocket launch.

Nothing is scripted. Each mind is only told what that person can see, feel,
remember and has heard. It decides what to think, what to say aloud, and a plan
of actions. The simulation then carries that plan out under the same physical
rules for everyone.

## Run it

```bash
cd world
npm install
npm run dev          # http://localhost:5175
```

Or open **`genesis.html`** (repo root) directly in a browser. It's the whole app
in one file (`npm run build:single` regenerates it). Once GitHub Pages is
enabled, it's also published at **`/world/`** next to the other apps.

Then click **✦ Add API key** and paste an Anthropic API key. The key is stored
only in your browser's `localStorage` and sent only to `api.anthropic.com`.
Without a key the world still runs: everyone acts on a built-in instinct brain.

## What's in the world

**Earth-like terrain.** Each world is generated from a seed. Elevation comes from
warped fractal noise with ridged mountain ranges. Temperature follows latitude
and altitude. Moisture comes from distance to the sea. Rivers run downhill from
wet highlands to the sea or a lake, and biomes are classified from all three:
ocean, shallows, beach, grassland, forest, rainforest, savanna, desert, taiga,
tundra, snow, marsh, hills, mountains and peaks. There are five presets:
*Temperate Continent*, *Fertile Crescent*, *Tropical Archipelago*, *Alpine
Highlands* and *Rift Valley*.

**Real resources and needs.** People need food, water, sleep and warmth. Sea
water is undrinkable, and winter nights kill the unsheltered. Resources sit
where they would in reality: trees and berry bushes, reeds, clay on river banks,
sand on beaches and deserts, ore (copper, tin, iron, coal, gold) in hills and
mountains, and oil in deserts and marshes. Trees and bushes regrow; ore runs out.
A tribe can only harvest land it has explored.

**Ten eras, 53 technologies, 32 building types, 24 recipes.** Knowledge runs
from fire and stone tools through agriculture, bronze, writing, iron,
philosophy, steel, printing, steam, electricity, computing and rocketry.
Production chains are real: ore → metal → tools; sand → glass; steel + iron →
machinery; copper + glass + gold → electronics. The workshop, kiln, smithy,
factory and similar buildings are the stations those recipes need. Libraries,
universities, labs and data centres multiply research.

**Lives and lineages.** Personalities use the Big Five plus intelligence and
strength. Children inherit their parents' traits with mutation. Skills grow with
practice. People keep memories, relationships (affinity and familiarity), homes
and partners. They age and die of old age, illness, starvation, thirst, cold or
violence, and the people who loved them grieve.

**Society.** Tribes share stores and knowledge. Leaders issue decrees (laws,
values, beliefs, even a new name). Anyone can found a new tribe or ask to join
another. Ideas spread between tribes through teaching, partnerships and
migration. Tribes keep relations with each other: allies, neutrals or rivals.
After Writing, people author works that are kept in the tribe's library.

**World events.** Weather, seasons, droughts, harsh winters, plagues, seasons of
plenty, earthquakes and golden ages.

## How the AI minds work

```
             ┌────────── system prompt (cached) ──────────┐
             │ how the world works · every action ·        │
             │ buildings · recipes · the whole tech tree   │
             └─────────────────────────────────────────────┘
 person ──▶  user turn: who you are · body · surroundings · people nearby ·
             tribe, laws, stores, research · memories · what you were told
                              │
                              ▼  Claude (structured JSON output)
             { thought, feeling, goal, say, say_to, plan: [1–6 actions] }
                              │
                              ▼
 simulation: speech is heard by listeners → plan steps become A* walks + timed work
```

- **When people think.** Each person thinks when their plan runs out, when
  someone speaks to them, after important events, and at least every *N* game
  hours. While a thought is in flight, or if the AI is off, their **instinct**
  keeps them alive: a utility AI that drinks, eats, sleeps, keeps warm, and
  works toward communal needs.
- **Reflection.** Every couple of in-game days, each mind distils its memories
  into lessons, a self-image and beliefs. Those evolve into a tribe's culture.
- **The historian.** Once a year a Claude chronicler writes a chapter of the
  world's history from that year's records.
- **Ask them anything.** Select a person to ask them a question. The answer
  comes from their own perspective, and it doesn't change the world: they won't
  remember you asked.
- **Mixed minds.** Choose a model per tribe, or per person, and see which
  civilisation does best: Claude Opus 5.5 (default), Sonnet 5.5, Haiku 5.5 or
  Fable 5.1.

Requests go through the official `@anthropic-ai/sdk` from the browser. They use
adaptive thinking at a chosen effort, structured outputs (a strict JSON schema),
a cached system prompt, and server-side refusal fallbacks where the model
supports them.

### Cost control

Every AI request costs money, so Settings has the following controls:

| Setting | What it does |
|---|---|
| Spending cap | Minds pause (instinct takes over) once this session's spend reaches it. Default **$5**. |
| People with Claude minds | Only this many adults get a mind. Leaders always get one. Default 16. |
| Max thoughts per minute / simultaneous | Hard rate limits. Faster game speeds **don't** cost more. |
| Re-think interval | Upper bound on time between decisions. |
| Model & effort | Haiku 5.5 is the cheapest by far; Opus 5.5 gives the richest characters. |

The settings panel shows a live **$/hour** estimate and the spend so far.

## Controls

| | |
|---|---|
| Drag / scroll / pinch | pan / zoom |
| Click | select a person, building or tile |
| `Space` | pause |
| `1`–`5` | speed 1× · 3× · 10× · 30× · 100× |
| `F` | follow the selected person |
| `WASD` / arrows | pan |
| `Esc` | deselect |

Panels: **✦ Minds** (live feed of thoughts and speech), **People**, **Tribes**
(values, laws, beliefs, stores, relations, written works), **Chronicle** (event
log plus the historian's chapters), **Knowledge** (tech tree per tribe) and
**Stats** (population, progress, food and generations). The minimap can show
territory, fertility, temperature or what a tribe has explored. Worlds auto-save
to IndexedDB every two minutes. You can also save named copies and
export/import JSON files.

## Architecture

```
src/
  sim/        the world — deterministic from its seed, no DOM
    worldgen.ts   terrain, climate, rivers, biomes, resources
    world.ts      the World: time, needs, life & death, births, tribes, events
    actions.ts    how each plan step resolves, walks (A*) and works
    instinct.ts   the fallback utility brain (incl. production-chain planning)
    path.ts       allocation-free grid A*
    data.ts · buildings.ts · tech.ts · time.ts · names.ts · save.ts
  ai/
    client.ts     Anthropic SDK calls, models, pricing, errors
    prompts.ts    system prompt, per-person context, JSON schemas
    mind.ts       scheduler: who thinks when, rate limit, budget, applying decisions
  render/
    renderer.ts   chunked per-pixel terrain (hill-shading, seasons, snow, coasts),
                  live entities, lightmap for night, weather, minimap
    sprites.ts    procedurally painted trees, rocks and 32 building types
  ui/             React panels, inspector, dialogs
  game.ts         the loop tying world, minds and renderer together
test/             node --test: worldgen, long instinct-only survival, AI layer, saves
```

```bash
npm test          # simulation + AI-layer tests (no API key needed)
npm run build     # type-check and bundle to dist/
```
