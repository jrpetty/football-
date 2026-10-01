# Holdout

A zombie-survival camp builder in the spirit of the old Facebook game
*The Last Stand: Dead Zone*, with a factory at its heart that owes a lot to
*Satisfactory*. You run a fenced camp seen from a 45° camera, give every
survivor a job, belt stations together into production lines, and send squads
into a 3D city where they only see what they can actually see. Hordes grow with
what you build. The long goal is to rebuild a broadcast mast in five phases,
call the coast for an evacuation, and hold out through the last night.

It is built to be played over weeks and months. A camp day lasts about eight
real minutes at normal speed, the milestone board runs to tier 8, and a careful
player reaches the final Signal phase somewhere past day 250.

**Play:** open [`../holdout.html`](../holdout.html) in a desktop browser. It is
one self-contained file (three.js and the post-processing stack are bundled in)
that runs offline and saves progress in that browser. It is built for PC first:
mouse and keyboard, a 1600×900 or larger window, and a dedicated GPU for the
High and Ultra presets. Settings also has Low and Medium for weaker machines.
Press `F1` in the game for the field manual.

## Controls

| Where | Input | Does |
| --- | --- | --- |
| Everywhere | Left-drag, WASD / arrows, screen edges | Pan (hold Shift to pan faster) |
| | Scroll, `+` / `-` | Zoom |
| | Q / E, middle-drag (right-drag in camp and on the map) | Rotate the camera |
| | `F1` | Field manual (pauses a run) |
| Camp | `B` `C` `I` `T` `P` `M` `G` `L` | Build, Crew, Items, Trade, Camp overview, Map, Progress, Log |
| | `F` / `H` | The wall / horde intel |
| | `Space`, `1` `2` `3` | Pause, 1×, 2×, 4× speed |
| | `R`, `Shift`, `Enter`, `Esc` | While placing: rotate, place several, confirm, cancel |
| | Click a station's output, then a station | Lay a belt between them |
| Supply run | Left-click, drag a box, `1`–`4`, `Tab` | Select survivors (double-tap a number to jump to them) |
| | Right-click | Move, attack a zombie, or do the default action on a container |
| | Left-click a container | Menu: search it (quiet) or break it down for materials (loud) |
| | `Z` `X` `C` `V` | Molotov, pipe bomb, noise maker, first aid kit |
| | `Enter`, `Space`, `F` | Extract at the van, pause, focus the selection |
| City map | `M`, `Esc` | Back to camp |

## The long game

### Milestones and tiers
The **Progress** panel (`G`) holds a board of eight tiers with three
milestones each. A milestone is a delivery from storage. Paying it unlocks
stations, belt tiers, walls, land, recipes or camp-wide bonuses. From tier 3
on, a tier also needs the Signal to have reached a given phase, so the mast
and the factory grow together.

| Tier | Name | Milestones | Needs |
| --- | --- | --- | --- |
| 1 | Foothold | Smelter (Forge), Conveyors (Belt Mk1), Palisade (log wall, two expansions) | |
| 2 | Workshop | Chemistry (Chemistry Lab, Ammo Press), Fabrication (Fabricator, Weapons, Tailor), The Signal (the mast) | |
| 3 | Power | Power (Generator, Floodlight, Turret), Electronics (Electronics Bench, Radio Tower), Sheet Metal (wall, Solar Array) | Signal phase 1 |
| 4 | Industry | Rubber Rollers (Belt Mk2, two expansions), Machining (Machine Shop), Research (Research Desk) | Signal phase 1 |
| 5 | Automation | Automation (automated stations +50%), Motor Belts (Belt Mk3), Outer Ring (four expansions) | Signal phase 2 |
| 6 | Fortress | Fortification (wall), Power Cells, Transmitter Coils | Signal phase 2 |
| 7 | Overdrive | Overclocking (power cores), Amplifiers, Field Rations (camp eats 15% less) | Signal phase 3 |
| 8 | Exodus | Convoys (outposts), Arsenal (better crafting quality), Beacon (more and better recruits) | Signal phase 3 |

The old goals live on as **starter tasks** under Progress, with a reward each.

### The Signal
The Signal Mast is a 20-metre lattice tower raised in five phases: clear it,
power it, raise the dish array, tune it, and call the coast. Each phase asks
for hundreds, then thousands, of components: steel, wiring, rubber, circuits,
motors, coils, power cells and amplifiers. Deliver by hand from the Progress
panel or belt parts straight into the mast. The tower visibly grows with each
phase. When the last phase is done, the biggest horde of the game comes for
the camp. Survive it and the convoy comes.

### Pacing
- **Threat** sets horde size and toughness. It comes from what the camp has
  achieved: tier, Signal phase, population and expansions, plus a little for
  days survived (capped at day 100).
- **Blood Moons:** from day 14, every seventh night brings a horde half again
  as big, with tougher and faster dead and a red sky.
- **Offline progress:** close the game and the crew keeps working at one
  fifteenth of real time, up to three camp days. The clock waits for you, so no
  horde comes while you are away. A report shows what was made and used.
- **Saves:** autosave every few seconds, a backup at the start of each of the
  last few days, and export, import and restore in Settings.
- A scripted player in a headless simulation reaches tier 2 around day 20,
  Signal phase 1 around day 30, phase 2 around day 60 and phase 3 between days
  150 and 200. Tier 8 opens near day 280.

## Production

### Belts
Every station has an input buffer and an output buffer. Workers carry goods by
hand. Belts carry them automatically: click a station's output, then the
station that should receive it. Belts run on posts above head height, so
people walk underneath, and the route is found around buildings with a
turn-aware path search. Where two belts cross, one runs a layer higher. Cargo rides visibly as crates, drums, logs, spools and sacks,
and a lamp on each belt shows whether it is flowing, waiting or backed up.

| Belt | Carries | Unlocked by |
| --- | --- | --- |
| Mk1: salvaged rubber on scrap rails | 120 a day | Conveyors (tier 1) |
| Mk2: proper rollers, cured belt | 320 a day | Rubber Rollers (tier 4) |
| Mk3: motor-driven, steel-framed | 800 a day | Motor Belts (tier 5) |

- A station runs 15% faster for each side that is fully belted (inputs fed,
  outputs carried away).
- A station whose output is belted to a consumer works **on demand**: it makes
  what the next station needs instead of whatever it has the materials for.
- A full output belt stops the station. The Camp overview names every
  bottleneck.
- Belts upgrade in place. Moving a station reroutes its belts. Logistics
  research makes every belt carry 25% more.

### The components ladder
- **Raw:** scrap, wood, water, food, fuel.
- **Refined:** metal, chemicals, gunpowder, cloth, parts.
- **Components:** steel (Forge level 2), wiring and electronics (Fabricator),
  rubber and power cells (Chemistry Lab), circuits, motors, transmitter coils
  and amplifiers (Machine Shop).

### Research and power cores
- The **Research Desk** studies **schematics** found on runs. Each study offers
  three alternate recipes to choose from, such as charcoal steel, pig steel or
  cast metal, 14 in all. They use different inputs, make more or skip a step.
- Projects cover field medicine, ballistics, scavenging, infected biology, an
  antiviral, immune boosters, a vaccine, power efficiency, logistics and core
  tuning. **Specimens** for the infection projects come from stalkers,
  screamers and bloaters.
- **Power cores** are rare finds. After the Overclocking milestone, an
  automated station takes up to three, each adding 50% speed (75% with Core
  Tuning). Its power draw rises faster than its output.

### Camp overview
The Camp overview (`P`) shows each resource's rate per day with a sparkline of
the last four days, storage fill, power and a list of what is stuck and why.

## Supply runs and sight

Each run builds the real building on its lot: typed rooms, doors, windows and
furniture, plus the yard, the street and the neighbours. Every container has a
loot table that fits it.

**You only see what your survivors see.**
- Each survivor casts sight every tick in a forward cone with some awareness
  behind. Walls, buildings, the van and tall shelving block it.
- Stepping into a room reveals the room. Explored areas stay on the map as a
  cold, dim memory, but zombies there are hidden until someone looks again.
  Unexplored areas are dark fog. Explored layouts are remembered between
  visits.
- Base sight is about 11 m in daylight. Night cuts it by up to half; a
  flashlight throws a beam and night-vision goggles cancel the penalty. Fog
  and rain shorten it.
- **Better eyes:** the Scout job and Thermal Goggles see movement through one
  wall (6 m and 8 m); the rare Sixth Sense trait does the same at 4 m. Sensed
  zombies glow through the wall. Eagle-Eyed, Hunter, Security Guard,
  binoculars and the Pathfinder perk see further.
- **Worse eyes:** Nearsighted sees 5 m less far, Night-Blind is nearly blind
  after dark, and Hard of Hearing hears 7 m less.
- **Hearing:** zombies out of sight leave a "last seen" mark, and anyone within
  earshot sees a ripple where one moves, even through walls. Keen Hearing,
  the Police Officer, the Ex-Con and the Drifter hear further.
- A **minimap** shows the explored layout, the squad, seen zombies and sound
  pings.
- **Special infected:** stalkers creep up while nobody is looking, screamers
  call every infected nearby, and bloaters burst into an infectious cloud.
  Hidden traps wait in the dark; scouts spot them from much further away.

Runs still work as before: up to four survivors with their own gear, the van
burns fuel (walking takes three times as long), noise draws the dead, packs
fill up and loot is only safe once it is in the van, and a horde timer runs on
every run. A downed survivor bleeds out in 40 seconds unless a teammate helps
them up.

## Survival

- **Infection:** a bite has a 6% chance to infect (a bloater's gas 4%). It
  climbs about 40 points a day: fever at 30 (slower work, less health), turning
  at 70 (half speed, no runs), and at 100 the survivor is lost. An antiviral
  cures it below 60. A staffed Infirmary slows it and gives antivirals by
  itself. Immune Boosters halve the risk and the Vaccine ends it.
- **Seasons:** four seasons of six days each. Spring fills the collectors,
  summer makes everyone thirstier, the autumn harvest is 40% bigger, and in
  winter snow covers the camp, the farms and collectors slow to a crawl and the
  camp burns 0.8 wood per survivor a day for heat (fuel if the wood runs out).
  Trees turn gold and red through the autumn.
- **Perks:** skills now rise to 15. At levels 5, 10 and 15 of each skill a
  survivor picks one of two perks.
- **Outposts:** after the Convoys milestone, claim up to four locations you
  have run. One to three survivors hold each one. Every day a convoy brings
  home what the place is good for (fuel from a gas station, food from a
  supermarket, meds from the hospital), and you can see the trucks drive the
  roads on the city map. Outposts upgrade twice and can come under attack.

## The camp

- You start in an old lumber yard with four survivors, a campfire, a tent
  bunkhouse, a farm plot, a rain collector, a storage depot and a workbench.
- Every survivor eats about 2 food and drinks 2.4 water a day. If food or water
  runs out, everyone weakens and works at 60%.
- Survivors without a job build whatever is under construction. When nothing
  is going up, they forage the yard for a little wood and scrap.
- Morale comes from beds, hot meals, the fire, the wall and recent events. High
  morale makes everyone work up to 20% faster. Below 18, people start leaving
  in the night.
- **Expansions:** each side of the fence can be pushed out twice. Each one costs
  materials and cash, takes a crew time to clear, and pays back salvage.
- **Survivors:** 25 pre-outbreak jobs (the Scout is new), eight skills,
  traits with strengths and flaws, a weapon, armor and gear slot each, item
  quality and condition, mods, and permanent death recorded in the Memorial.
- **Crafting benches** work through an order queue (once, N times, or keep
  stocked) and keep the crew's gear repaired.
- **Automation:** give a station of the right level an Automation Module and
  power, and it runs with nobody assigned.
- The **black market** buys and sells. Its stock changes at dawn.
- **Distress calls and supply drops** appear on the city map for a while.

## Development

```bash
cd holdout
npm install
npm run build        # writes ../holdout.html
```

The code is plain ES modules in `src/`. `build.mjs` bundles them with esbuild
into one HTML file, and `node build.mjs --out FILE --fragment` writes just the
page body. `node dev-build.mjs src/dev/<viewer>.js out.html` builds the model,
character, station and lighting viewers used while modelling.

```
src/
  main.js            boot, title screen, scene switching, offline catch-up,
                     main loop
  game/
    data.js          every definition: resources, jobs, skills, perks, items,
                     stations, recipes, belts, milestones, the Signal,
                     research, seasons, infection, outposts, locations, zombies
    state.js         game state, survivors, milestones, research, outposts,
                     saving, backups, export and import
    economy.js       the camp simulation: production, crafting, power, morale,
                     seasons, infection, threat, hordes, outposts, the market
    belts.js         belt routing, rates, buffers and the belt simulation
  scenes/
    base*.js         the camp: stations, people, belts, fence, horde fights
    citymap.js       the 3D city map, squad planner and convoys
    mission*.js      supply runs: interiors, looting, sight, traps, extraction
  world/
    city.js          seeded city layout: streets, districts, lots, locations
    levelgen.js      supply-run levels: floor plans, rooms, furniture, loot
    vision.js        line of sight, room reveal, wall sense, memory
    agents.js        survivor and zombie AI and combat
  models/            procedural models: skinned characters, stations, the
                     mast, furniture, weapons, vehicles, city
  render/            pipeline (SSAO, bloom, SMAA, tilt-shift), fog of war,
                     sky, terrain, snow and seasons, materials, effects
  ui/                HUD, panels, progress board, field manual
  core/              audio, A* grid, utilities
```

[`DESIGN.md`](DESIGN.md) has the design analysis behind v3 and notes on where
the shipped game differs from the plan.
