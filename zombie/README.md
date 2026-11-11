# 🔥 REKINDLE

**Survive the dead. Rebuild the world.**

REKINDLE is an open-world, first-person zombie survival game that runs in the
browser. It starts as a tense, Project Zomboid–style fight to stay alive in a
ruined Pacific Northwest town. Over time it turns into Satisfactory-style
factory building: power grids, conveyor lines and automated defences, until you
synthesise a cure and switch the town's lights back on.

Built with Three.js, TypeScript and Vite. There are two art styles (Settings →
Art style): **Realistic** (the default: PBR textured materials, smooth shading,
textured terrain, needle-and-leaf trees, a physical scattering sky, reflective
water, film grain) and **Low-poly** (faceted flat colours in the spirit of
*The Long Dark*, painterly skies and puffy clouds). Everything is procedural: terrain, town,
interiors, 3D models, sound effects and music. The repo has no image or audio
files.

---

## Play

**No install:** download `rekindle.html` and double-click it. It's the whole
game in one file (about 1.2 MB). It needs a desktop browser with WebGL2
(Chrome, Edge or Firefox).

**From source:**
```bash
cd zombie
npm install
npm run dev            # http://localhost:5174
npm run build          # production build in dist/
npm run build:single   # regenerate rekindle.html
```

Graphics presets (Settings): **Low / Medium / High / Ultra**. High adds
ambient occlusion (GTAO), 4K shadow maps, denser grass and longer view
distance. Start on Medium on laptops.

---

## The loop

1. **Day 1. Scavenge.** Search the ranger cabin. Then work through houses,
   the supermarket, pharmacy, clinic, police station and hardware store.
   Searching each container takes time, and a lot of them are already empty.
2. **Survive.** Manage hunger, thirst, fatigue, body temperature and wetness.
   Wounds are tracked per body part: bleeding, infection and fractures. A bite
   means the Knox infection, which kills you in about 2.5 days unless you find
   the cure. Death is permanent.
3. **Fortify.** Barricade windows and doors with a hammer, planks and nails.
   Build walls, doors, foundations, stairs, watchtowers, campfires, rain
   collectors, beds and farm plots.
4. **Establish the Outpost.** The Survivor Outpost is your HQ and tech tree.
   Deliver parts to it to complete milestones.
5. **Automate.** Put miners on ore deposits and run conveyor belts into
   smelters, constructors and assemblers. Split and merge lines. Power it all
   with biomass burners, coal generators, solar and wind, linked by power poles
   (watch for tripped fuses).
6. **Defend.** Machines make noise. Your base's **heat** draws hordes to it,
   so build auto turrets fed with factory-made ammo, electric fences, spike
   traps and floodlights.
7. **Rekindle.** Restore the town grid with the Grid Uplink. Build a Radio
   Tower for supply drops. Collect infected tissue from the dead, synthesise
   **10 Antiviral Serum** in the Medical Lab and deliver it to the Outpost to
   win.

### Milestones
| Tier | Name | Unlocks |
|---|---|---|
| 1 | Foothold | Biomass burner, power poles, miner, smelter, belts, storage, floodlights |
| 2 | Industry | Constructor, coal generator, splitter/merger, electric fence, metal walls, gate, watchtower |
| 3 | Fortify | Assembler, auto turret, solar, foundry, batteries, concrete walls, ammo crafting |
| 4 | Automation | Belt Mk.2, Miner Mk.2, wind turbine, greenhouse, radio tower, water pump, pipes, fluid tank, packager |
| 5 | Rekindle | Medical lab, Grid Uplink, oil pumpjack, refinery, fuel generator |
| 6 | The Cure | Victory |

---

## Systems

- **Survival.** Hunger, thirst, fatigue, stamina, body temperature
  (clothing insulation, rain, fire, shelter), wetness, pain, panic,
  unhappiness and food poisoning. Six body parts track their own wounds:
  scratch, laceration, bite, fracture. Wounds bleed, get infected and heal
  over time. There are 16 Zomboid-style moodles.
- **Medicine.** Rags and sterile bandages, disinfectant, sutures, splints,
  painkillers, antibiotics and the serum.
- **Character.** Eight occupations and 18 positive and negative traits that
  cost or give points. Eleven skills level up through use (Fitness, Strength,
  Sprinting, Lightfooted, Blunt, Blade, Firearms, Carpentry, Engineering,
  First Aid, Scavenging). Skill books triple XP gain for a day.
- **Zombies.**
  - Rendering: hundreds of instanced zombies animated on the GPU in one draw
    call.
  - Types: walkers, runners and brutes.
  - Senses: sight depends on light, crouching and your flashlight. Hearing
    picks up footsteps, gunfire, chopping, breaking glass and machines.
  - Pathing: they follow a flow field around buildings and bash through doors,
    barricades, walls and machines.
  - Hordes migrate at night or home in on a noisy base. Four or more grabbing
    you at once can drag you down.
- **Combat.** Melee has swing arcs, headshots, knockback, shoves and weapon
  durability. Firearms have spread, recoil, aim-down-sights, magazines and
  reloads. Every gunshot can be heard for blocks.
- **World.** 800 × 800 m with a town of furnished buildings: locked doors,
  breakable windows you can climb through, and lootable fridges, cabinets,
  lockers and car trunks. Forests are choppable and there's a lake. The day/night
  cycle and dynamic weather include rain, storms with lightning and fog.
- **Graphics.** A polished low-poly art direction: every surface is a flat,
  faceted colour from a muted palette, lit by the atmosphere.
  - Facets: each face gets subtle tonal variation, with per-object warm/cool
    drift and baked-looking sky bounce, so surfaces read as hand-painted.
  - Sky and depth: a painterly gradient sky that melts into the fog, three
    layers of distant low-poly mountains with atmospheric perspective and
    snow caps, puffy low-poly clouds plus a crisp high cloud layer, valley
    mist that pools in low ground, stars and moon.
  - Light: sky-lit environment maps, soft shadows, bloom, ACES tone mapping
    and a colour grade that warms at golden hour and cools to teal at night.
  - World: faceted terrain; low-poly conifers, autumn birches, snags, bushes
    and boulders with per-tree colour variety, wind sway and snow cover;
    chunky GPU grass with seasonal wildflowers; a heaving low-poly lake.
  - Ambient life: tumbling autumn leaves, fireflies on warm nights, sunlit
    dust, rain and snow, blood decals and particles.
- **Animation.**
  - First person: a spring-physics weapon rig with forehand, backhand and
    overhead swings, knife stabs, heavy overhead chops, staged reloads
    (magazine out, magazine in, rack), equip raise, breathing, sprint
    figure-eights, recoil that kicks and settles, and jump, landing, hit
    and hurt jolts. The camera rolls when you strafe, dips when you land
    and follows the weight of each swing.
  - Zombies: GPU-animated skeletons with shambling, limping and flailing
    runner gaits, heads that track you, twitches, clawing grabs, hit
    reactions that depend on where you strike, three death styles (knees
    buckle, topple with momentum, twist sideways), and crawlers that drag
    themselves along when badly hurt.
  - Humans: your third-person survivor and NPC survivors have natural
    walk and run cycles, aiming poses and overhead melee swings.
  - Animals: articulated deer, rabbits and wolves that walk, trot, gallop
    and bound, graze with their heads down, track you when alert, flash
    tails when fleeing and wag them when stalking.
  - Vehicles: the body leans in turns, squats and dives under throttle and
    braking, and jolts over bumps.
- **Audio.** All sound is synthesised with Web Audio:
  - HRTF positional sound effects.
  - Ambience that changes with weather and time of day.
  - Adaptive music that rises from calm to chase.
- **Vehicles.** Drive the abandoned cars: find keys or hotwire them, refuel
  from gas cans, siphon fuel, fill cans at gas pumps once the town grid is
  back, plough through zombies (it damages the car), repair with kits. Chase
  or in-car camera.
- **Third-person view.** Toggle with V to see your survivor.
- **Seasons.** Autumn, winter, spring and summer, 7 days each. Winter brings
  frost, snowfall that settles on the ground and trees, and stops outdoor
  crops growing.
- **Food spoilage and power.** Fresh food rots. A portable generator lights
  the house it sits in and keeps its fridge cold (and is loud enough to
  draw zombies); the Grid Uplink powers every fridge in town.
- **Wildlife.** Deer and rabbits to hunt and butcher, and wolf packs that
  stalk you, especially at night. Fish the lake with a rod.
- **Survivors.** Recruit people you find (and people your radio tower
  attracts) with food. Order them to follow you, guard the Outpost, go
  scavenging or wait. They fight, eat from your stores daily, can die, and
  leave if you can't feed them.
- **Fluids.** Pipes, a lake water pump, tanks and a packager that bottles
  clean water automatically; later oil pumpjacks, a refinery (fuel, plastic,
  rubber) and fuel generators.
- **Saving.** Autosaves every three game hours and when you sleep. F5 saves
  manually. Saves go to `localStorage`.

## Controls
| Action | Key |
|---|---|
| Move / sprint / crouch / jump | WASD / Shift / C / Space |
| Attack · aim (guns) or shove (melee) | LMB · RMB (V shoves) |
| Interact · secondary action · reload · flashlight · quick bandage | E · T · R · F · Q |
| Camera (third person on foot, chase/in-car when driving) | V |
| Drive · steer · brake · get out | W/S · A/D · Space · E |
| Hotbar | 1–8 / mouse wheel |
| Inventory & crafting · health · skills · journal · map | Tab · H · K · J · M |
| Build menu · rotate · dismantle · exit build | B · R · X · RMB |
| Save · pause | F5 · Esc |

All keyboard keys can be rebound in Settings.

## Code map
```
src/
  game.ts            orchestration: loop, interaction, combat, timed actions, objectives, save/load
  core/              utilities (RNG, simplex noise, event bus), input
  render/            renderer + post FX, sky/atmosphere, weather, particles, light pool, procedural textures & materials
  world/             layout, terrain (splat shader), town generator, vegetation, collision + nav grid, world queries
  ai/                zombie GPU mesh/shader and AI director, wildlife, survivor NPCs
  vehicles/          drivable car physics, fuel, collisions
  player/            controller, survival stats, skills/traits/occupations, first-person viewmodel
  items/             item database, inventory, loot tables, crafting recipes
  factory/           machine/recipe/milestone definitions and the factory simulation (belts, power, turrets)
  build/             player structures and the holographic placement system
  models/            procedural 3D models: weapons, props, cars, trees, machines, build pieces
  audio/             procedural Web Audio engine: SFX, loops, ambience, adaptive music
  ui/                DOM HUD, menus and panels
preview/             dev galleries for textures, models, machines and audio (npx vite --config preview/vite.config.ts)
```
