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

The app opens on a **title screen** over the living world. Click **Minds &
providers** to connect AI. Every person can be driven by:

- **Claude**: Opus 5.5 (default), Sonnet 5.5, Haiku 5.5 or Fable 5.1, through the official Anthropic SDK
- **Chinese model APIs**: DeepSeek, Qwen (Alibaba Cloud), Kimi (Moonshot AI), GLM (Zhipu AI)
- **Models you download and run yourself**: Ollama, LM Studio, llama.cpp, vLLM, or any OpenAI-compatible server
- **Instinct**: no key needed. A built-in brain keeps everyone alive and busy.

Assign a different mind to each tribe, or to individual people, and see which
civilisation does best. Keys stay in your browser's `localStorage` and go only
to the provider you chose.

Most cloud APIs (DeepSeek, Qwen, Kimi, GLM) refuse calls made directly from a
web page. Run `npm run proxy` in this folder and tick **Use local proxy** on
those minds. The proxy listens only on `localhost:8787`. For Ollama on the
hosted site, start it with `OLLAMA_ORIGINS=*`. For LM Studio, enable CORS in
its server settings. **Test connection** and **List models** in the settings
check your setup.

**No two histories are the same.** A map seed reproduces the land, but the
people, their choices and their discoveries are new on every run. Untick
*Unique history* when creating a world to replay a seed exactly.

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
water is undrinkable, and winter nights kill the unsheltered. Trees, berry
bushes, reeds, clay on river banks and sand on beaches and deserts sit where
they would in reality. Trees and bushes regrow. A tribe can only harvest land
it has explored.

**Geology: minerals are underground, where real geology puts them.** Every
world is seeded with ore bodies, each with a mineral, reserves and a depth:

- copper, tin, gold and uranium in the roots of mountain ranges
- great iron formations in old, worn-down hills
- coal under low basins of buried swamp forest
- oil deep under deserts, marshes and plains, and under the continental shelf
- bauxite under hot, wet forests, and lithium brine under desert salt flats

Only a few bodies break the surface as outcrops (plus gold gravels along
mountain rivers and bog iron in marshes). Most must be **found by
prospecting**:

- a prospector's eye spots shallow bodies near surface showings
- Geology reads the strata over wide areas
- seismic surveys see kilometres down and under the sea

Then they have to be **dug**:

- a pit or adit for shallow ore
- Shaft Mining for deep ore
- steam-pumped Deep Mining for the deepest seams
- oil wells and offshore platforms for oil, and evaporation ponds for lithium

Bodies run out, mines are dangerous, and the minds are told what their people
have found and how deep it lies. A **Geology** overlay shows what lies beneath:
every body, with the ones a tribe has found shown strongest.

**The sea.** Fish live where the water is rich: the coastal shallows and the
upwelling edge of the shelf most, open ocean less, lakes and rivers least.
Stocks regrow at about the rate real fisheries do. Fishing moves on with
technology:

- from the bank
- dugout canoes on lakes and shallows
- sailing boats on the open sea
- ocean ships in deep water
- steam trawlers, then diesel trawlers with sonar

Each step catches far more, and the trawl nets destroy more than they keep.
Overfished water empties, and a **Fish stocks** overlay shows it happening.
Fish farms (Aquaculture) take the pressure off. In the 3D view the boats change
with the era.

**Ten eras and 126 technologies, learnt the way humans learnt them.** The tree
runs from fire to reusable rockets: copper smelting, the alphabet, crop
rotation, the compass, the blast furnace, shaft mining, optics, geology, coke
smelting, steamships, germ theory, dynamite, oil drilling, Haber–Bosch
fertiliser, plastics, aluminium smelting, nuclear power, the transistor, wind
and solar, lithium batteries, genetic engineering, mRNA vaccines, artificial
intelligence, satellites and GPS. Each one changes how fast people farm, fish,
mine, build, craft, travel, heal or research. Early
knowledge comes from practice. Every hour of relevant work brings an idea
closer:

- working clay by a fire teaches pottery
- tending wild plants teaches farming
- twisting fibre teaches cordage

Half-understood ideas sometimes click in a **flash of insight**.
Deliberate study drives the harder ideas, but before Writing it is only half
as effective.

**Geography shapes what comes first.** Coasts suggest boats, ore in the hills
suggests metalwork, and fertile river land suggests farming. **Knowledge is
fragile:** a people that dwindles to a handful before inventing writing can
forget what it knew. Each era is harder to reach than the last. The Stone Age
lasts about a decade of game time, and the space age takes generations.

Production chains are real:

- ore → metal → tools; iron ore + coal → coke-smelted iron
- sand → glass; steel + iron → machinery
- copper + glass + gold → electronics; crude oil → fuel and plastics at a refinery
- bauxite → aluminium (electrolysis); lithium + copper + plastic → batteries
- uranium → nuclear power

Industry needs power: coal plants, then wind turbines, solar farms and nuclear
stations. Later come airports, radio masts and harbours.

**Peoples, alliances and war.** Tribes meet when their lands touch, and each
holds an opinion of the others that moves with real causes:

- settlements and borders pressing together, and a filling world
- competition for the same ore and fishing grounds
- hunger beside plenty
- trade and marriages across the line
- shared enemies, old grievances, cultural kinship
- the temper of their leaders

**What leaders can do.** Trade pacts move surplus goods and ideas between
peoples. Alliances call allies to arms. Leaders also send gifts, demand tribute
from the weak and sue for peace. They go to war over grudges, hunger, ore they
need, or ambition.

**How wars are fought.** Wars are fought by the people themselves:

- warriors muster at their hub and march on the enemy as a visible column with
  a banner bearer
- they fight battles decided by numbers, health, weapons, barracks, walls and
  home ground
- weapons follow technology: clubs, spears, bows, bronze, iron and steel
  blades, muskets, rifles, armour, aircraft, guided weapons, drones

**How wars end.** Victorious raiders carry off stores and set buildings alight,
and the fallen are really dead. Peace brings tribute for the winner, and a
broken people can be conquered outright, its homes, stores and knowledge
passing to the victor. AI leaders make these decisions through a `diplomacy`
action; instinct leaders follow their temperament.

**Seeing it.** A Diplomacy overlay draws alliances, trade, tribute and war
between towns, armies on the march are always shown, and the Tribes panel shows
every relationship, the balance of power and how each war is going.

**True scale.** A tile is about six metres. People stand about a third as tall
as a cottage, trees tower over them, doors fit them, and animals, grass and
crops are life-sized.

**Lives and lineages.** Personalities use the Big Five plus intelligence and
strength. Everyone looks like an individual:

- skin and hair inherited from their parents
- their own hairstyle, beard, build and height
- face paint and scars
- clothing that changes with the era
- a circlet for leaders, a cane for elders

Emotes show discoveries, love, grief and anger. Painted portraits read each
person's temperament, mood, tiredness, sickness and age. Children inherit
looks and traits with mutation. Skills grow with
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
- **Mixed minds.** Choose a mind per tribe or per person, mixing Claude, DeepSeek, Qwen, Kimi, GLM and local models, and see which civilisation does best.

Claude requests go through the official `@anthropic-ai/sdk` from the browser. Other providers use the standard `/chat/completions` endpoint in JSON mode, and any reasoning a local model writes out inline is stripped from its replies. Claude requests use
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
| Right-drag (or `Alt`+drag) · `Q` / `E` | turn the camera (3D view) |
| Click | select a person, building or tile |
| `Space` | pause |
| `1`–`5` | speed 1× · 3× · 10× · 30× · 100× |
| `F` | follow the selected person |
| `WASD` / arrows | pan |
| `Esc` | deselect |

The world is drawn in **3D** (WebGL): sculpted terrain, rivers, lakes and sea,
swaying seasonal forests, buildings modelled for each era, jointed people and
animals, a sun that crosses the sky with real shadows, night lighting, weather
and bloom. The **🗺️ 2D** button in the top bar switches to the classic
hand-painted map (also used automatically if WebGL isn't available).

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
    providers.ts  mind profiles: Claude + OpenAI-compatible (DeepSeek, Qwen, Kimi, GLM, Ollama, LM Studio)
    prompts.ts    system prompt, per-person context, JSON schemas
    mind.ts       scheduler: who thinks when, rate limit, budget, applying decisions
  render3d/   the 3D view (three.js)
    renderer3d.ts camera, sky, sun & shadows, night lamps, picking, labels, minimap
    terrain.ts    heightfield, seasonal colours, water levels, ocean, roads, borders
    flora.ts      instanced trees, rocks, ores, grass with wind sway
    buildings3d.ts  models for all 32 building types across the eras
    character.ts  jointed 3D people and animals
    fx.ts         particles, rain & snow, clouds, birds
  render/     the classic 2D view
    renderer.ts   chunked per-pixel terrain (hill-shading, seasons, snow, coasts),
                  live entities, lightmap for night, weather, minimap
    sprites.ts    procedurally painted trees, rocks and 32 building types
    people.ts     the animation library shared by both views
  ui/             React panels, inspector, dialogs
  game.ts         the loop tying world, minds and renderer together
test/             node --test: worldgen, long instinct-only survival, AI layer, saves
```

```bash
npm test          # simulation + AI-layer tests (no API key needed)
npm run build     # type-check and bundle to dist/
```
