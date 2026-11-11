# 🔥 REKINDLE

**Survive the dead. Rebuild the world.**

REKINDLE is an open-world, first-person zombie survival game that runs in the
browser. It starts as a tense, Project Zomboid–style fight to stay alive in a
ruined Pacific Northwest town. Over time you take in survivors, give them homes
and jobs, and grow a settlement from a camp into a city, with its own mines,
workshops, power and railways. The goal is simple: build a city and keep it
alive.

Built with Three.js, TypeScript and Vite. There are two art styles (Settings →
Art style): **Realistic** (the default: PBR textured materials, smooth shading,
textured terrain, needle-and-leaf trees, a physical scattering sky, reflective
water, film grain) and **Low-poly** (faceted flat colours in the spirit of
*The Long Dark*, painterly skies and puffy clouds). Everything is procedural: terrain, town,
interiors, 3D models, sound effects and music. The repo has no image or audio
files.

---

## Godot version

A Godot 4 port lives in [`godot/`](godot/README.md): open that folder with Godot 4.4+ and press Play. It is being
rebuilt in stages from this web version — the world, survival, looting, combat and the dead are in; building,
machines, railways and the settlement are still to come. Its content (models, world, items, sounds, characters)
is exported from this code by `scripts/godot-export/`.

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
   means the Knox infection, which kills you in about 2.5 days unless you take
   the serum. Death is permanent.
3. **Fortify.** Barricade windows and doors with a hammer, planks and nails.
   Build walls, doors, foundations, stairs, watchtowers, campfires, rain
   collectors, beds and farm plots.
4. **Found a settlement.** Build the Settlement Hall, then a Shelter House.
   Win over survivors with food and send them home. Each resident takes a job
   on their own: farmer, cook, guard, lumberjack, mechanic or medic.
5. **Keep people alive.** Residents eat every evening (a hot meal from the Mess
   Hall keeps them happy) and need clean water (Water Tower). They need a bed.
   Newcomers walk in each morning if there's room, food and a good mood. People
   leave when they go hungry, thirsty or are miserable.
6. **Dig in and power up.** Ore drills on mineral seams, furnaces, machine
   shops, conveyors, boilers and power poles turn scrap into steel, glass,
   concrete and motors.
7. **Defend.** Machines make noise, and your town's **heat** draws hordes. Build
   guard posts (a resident stands watch with your ammo), turrets, walls, gates,
   electric fences and floodlights.
8. **Become a city.** Lay railways out to far mines and farms, restore the old
   town grid, and grow to 20 happy residents behind walls that hold.

### Settlement levels
Each level needs supplies delivered to the Settlement Hall *and* a living town.

| Level | Your town needs | Unlocks |
|---|---|---|
| Outpost | 1 resident, 4 beds | Wood boiler, power poles, ore drill, furnace, conveyors, storage, floodlights, mess hall, water tower, lumber yard |
| Settlement | 3 residents, 6 beds, 20 food | Machine shop, coal steam engine, splitter/merger, electric fence, metal walls, gate, watchtower, repair shop, clinic |
| Village | 6 residents, 4 defences, 50% happy | Fabrication shop, turrets, solar, steel mill, batteries, concrete walls, bunkhouse |
| Town | 10 residents, 40 MW, 55% happy | Powered conveyor, deep drill, wind, greenhouse, radio tower, water pump, pipes, tanks, packager, sprinklers |
| Railhead | 14 residents, 60 food | Railway track, freight stations, locomotives, wagons |
| Lights On | 17 residents, 8 defences, 60% happy | Grid Uplink, oil pumpjack, refinery, fuel generator |
| City | 20 residents, town grid on, 10 defences, 65% happy | You've built a city. Now keep it standing. |

---|---|---|
| 1 | Foothold | Biomass burner, power poles, miner, smelter, belts, storage, floodlights |
| 2 | Industry | Constructor, coal generator, splitter/merger, electric fence, metal walls, gate, watchtower |
| 3 | Fortify | Assembler, auto turret, solar, foundry, batteries, concrete walls, ammo crafting |
| 4 | Automation | Belt Mk.2, Miner Mk.2, wind turbine, greenhouse, radio tower, water pump, pipes, fluid tank, packager, sprinklers |
| 5 | Logistics | Railway track, freight stations, locomotives, freight wagons |
| 6 | Rekindle | Medical lab, Grid Uplink, oil pumpjack, refinery, fuel generator |
| 7 | The Cure | Victory (10 serum + 10 computers) |

---

## Systems

- **Survival.** Hunger, thirst, fatigue, stamina, body temperature
  (clothing insulation, rain, fire, shelter), wetness, pain, panic,
  unhappiness and food poisoning. Six body parts track their own wounds:
  scratch, laceration, bite, fracture. Wounds bleed, get infected and heal
  over time. There are 16 Zomboid-style moodles.
- **Medicine.** Rags and sterile bandages, disinfectant, sutures, splints,
  painkillers, antibiotics, and the serum the Clinic brews from infected
  tissue, the only thing that stops a bite from turning you.
- **Character.** Eight occupations and 18 positive and negative traits that
  cost or give points. Eleven skills level up through use (Fitness, Strength,
  Sprinting, Lightfooted, Blunt, Blade, Firearms, Carpentry, Engineering,
  First Aid, Scavenging). Skill books triple XP gain for a day.
- **Zombies.** They were people, and they keep rotting.
  - Six kinds. Walkers, runners and brutes from day one; then **howlers**
    (day 3: frail, but their scream calls and enrages everything within
    46 m), **bloats** (day 5: slow, and they rupture when killed with a body
    shot — a headshot drops them clean) and **breakers** (day 8: one arm
    fused into a club that tears down what you built).
  - Decay. The newly dead are fresh: fast, strong, almost human to look at.
    Over about two days they slow into shamblers, and after a fortnight
    into dry, brittle husks. Clothes, skin, hair, wounds and gait all follow.
  - Senses. A sight cone that shrinks in the dark and when you crouch;
    hearing for footsteps, gunfire, chopping, glass and machines; and smell
    — if you are bleeding they find you from further off.
  - Habits. By day about half of them stand dormant or lie among the
    corpses until something disturbs them; at night all of them wake and
    roam further. Loose packs drift together. A lost target is searched for
    where it was last seen, then given up.
  - They hunt your residents as well as you, kneel to feed on the dead, and
    crowd a door only as far as there is room to reach it.
  - Injury. Leg hits make crawlers, arm hits take the arm off, a hard blow
    staggers or floors them and they get back up, sprinters trip.
  - Turning. Anyone who dies infected — a resident, or you — rises where
    they fell wearing the same clothes and the same face, and keeps their
    name ("what's left of Marcus").
  - Pathing: they follow a flow field around buildings and bash through
    doors, barricades, walls and machines. Hordes migrate at night or home
    in on a noisy base. Four or more grabbing you at once can drag you down.
  - Rendering: one instanced, skinned body for every zombie and person —
    hundreds on screen in one draw call.
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
  - Bodies: an 18-bone skeleton posed per body, feet planted by leg IK so
    nobody slides. Zombies have five walking gaits plus sprint, stomp, stalk
    and waddle, turn in place, sniff, slump and paw at walls, lunge with a
    wind-up, crawl, climb back to their feet from their back or their face,
    and fall five different ways.
  - People breathe, blink, shift their weight, glance around, and when their
    hands are empty settle into stances of their own — arms folded, hands
    on hips, hands clasped. Working poses (swinging an axe or hammer,
    kneeling at a crop, stirring a pot, carrying, talking, waving, eating,
    sitting, cowering) use the tool actually in their hand.
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
- **People.** Everyone you meet is an individual, and stays one.
  - How they look: skin, hair (cropped, long, tied back, beanie, cap,
    bearded, greying with age), build and height are their own, and so are
    their clothes — tee, button shirt, jumper or flannel, with a jacket,
    gilet or rolled sleeves, a pack or a tool belt depending on the life
    they came from.
  - Who they are: a name, an age, a past life (nurse, logger, deputy, line
    cook, teacher… 18 backgrounds) that sets what they already know and how
    they dress, and two or three temperament traits out of 20 (Brave,
    Skittish, Hard worker, Idler, Cheerful, Brooding, Quick study, Night owl,
    Green thumb, Steady hands, Loner, Thick blood…).
  - Skills and levels: eight skills (Marksmanship, Brawling, Farming,
    Cooking, Handiwork, Medicine, Scavenging, Labour) grow from 0 to 10 with
    use. Skill sets how much work gets done, how well they shoot and whether
    they come back from a scavenging run. Each person takes to one or two
    skills (★: learns 50% faster, enjoys the work) and may resent a job. An
    overall level and rank (Newcomer → Pillar of the town) adds health and
    nerve.
  - Minds of their own: they path around buildings and through doors, keep
    their own hours (early risers, night owls, a night watch), take breaks,
    haul and stack when there's nothing better to do, greet you, and say what
    they think in speech bubbles.
  - Mood: a list of thoughts you can read ("Ate a hot meal", "Lost a friend",
    "Resents being made cook") adds up to happiness. Friendships and grudges
    grow out of evening conversations; friends move in together and grieve.
  - Danger: each person weighs their nerve — temperament, skill, weapon,
    wounds, friends nearby, how many dead are coming — and fights, holds a
    line or bolts for home shouting the alarm.
  - The bite: a zombie hit can break the skin. A bitten resident sickens over
    about 30 hours (bitten → feverish → delirious) and then turns, in the
    middle of your town. Antiviral serum cures it; a medic with antibiotics
    only buys time. Anyone killed by the dead gets back up within a minute or
    two unless you lay them to rest first.
  - Orders: talk to someone to have them follow you or live in town; open
    their profile (alt-interact, or Settlement Hall → Residents) for skills,
    thoughts, friends, job pinning, scavenging runs, ammo and serum.
- **Town life.**
  - Buildings: Shelter Houses (4 beds) and Bunkhouses (10), the Mess Hall,
    Water Tower, Lumber Yard, Repair Shop, Clinic and Guard Posts.
  - Daily routine: residents walk to work in the morning, break at noon,
    eat together at the mess hall, sit by the fire or talk in the evening and
    go home to sleep. When the bell rings, fighters rally to the threat and
    everyone else runs for their own front door.
  - Jobs go to whoever suits them best, and a better-qualified newcomer will
    take over a post. Farmers hoe, water, harvest and replant (4–9 plots each
    by skill); cooks turn stored ingredients into stove meals; guards shoot
    from their posts in day and night watches; lumberjacks walk out to fell
    timber and haul it back; mechanics walk to whatever is most damaged and
    mend it; medics go to the wounded, treat bites and make bandages.
  - Happiness comes from a bed, hot food, water, amenities, lights, feeling
    safe and good health. It sets how fast people work and whether newcomers
    come. Manage everyone from the Settlement Hall's Residents tab, where you
    can also pin someone's job.
- **Resources and mining.** About 90 ore nodes are scattered across the
  map: iron, copper, coal, limestone, sulfur, quartz, bauxite and gold, plus
  crude oil wells. Each seam is thin, normal or rich, which scales drill
  output and shows in the size of the deposit. Boulders in
  the wild carry ore veins you can pick at by hand.
- **Production chains.** Over 60 new items and dozens of machine recipes:
  glass, sand and silica from quartz; alumina and aluminium from bauxite
  (refinery plus foundry); gold wiring; steel beams, pipes and rails; stators,
  motors, computers, heavy frames and battery cells; flour, cloth and jars.
- **Farming.** Eight crops (carrot, potato, corn, wheat, tomato, cabbage,
  pumpkin, hemp), each with its own growing time and seasons. Plots need
  water (rain, a watering can, or a sprinkler on a pipe network), and
  fertiliser speeds growth. Farming skill raises yields and gives back seeds.
  Compost bins turn rot into fertiliser, chicken coops turn grain into eggs,
  and drying racks turn meat into jerky.
- **Cooking and foraging.** A wood stove and campfire cook fresh bread, stews,
  soups, omelettes, pumpkin pie, fish stew, jam, preserves, herbal tea and
  poultices, and many meals give buffs. Bushes and the forest floor give
  berries, mushrooms (some of them odd) and herbs. Hunting gives hides you can
  tan into leather for boots, gloves, a winter coat and a fur hat.
- **Weapons and gear.** Spears, steel axes and pickaxes, a quiet crossbow
  with bolts you can pick up again, scrap armour, a water filter and a
  survival pack.
- **Throwables.** Molotovs leave a burning patch that blocks a doorway for
  ten seconds. Pipe bombs explode with knockback and draw every zombie
  nearby.
- **Railways.** Drag to lay track. Corners curve on their own, junctions let
  trains choose a route, and dead ends get buffer stops. Put Freight Stations
  beside the track and set each one to *Load* (belt items in) or *Unload*
  (items go out onto a belt). A locomotive with freight wagons coupled behind
  it follows a schedule of stations:
  - It finds its route over the network and slows down to stop at each
    station.
  - It reverses out of dead ends.
  - It transfers cargo at each stop and refuels with coal stored at
    stations.
  - Wagons show their load.

  Trains run zombies down and will hurt you if you stand on the line. Press
  T on a locomotive to ride in the cab (E to step off, Q for the train
  panel), or E to open the panel, where you edit the schedule, add coal,
  rename the train and open wagons.
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
  ai/                the shared character body: skeleton + poses (bodyRig.ts), mesh (bodyGeo.ts), skinning + clothing shader (zombieMesh.ts); zombie AI director (zombies.ts), wildlife; people: personas & skills (people.ts), behaviour (survivors.ts), pathfinding (pathing.ts)
  vehicles/          drivable car physics, fuel, collisions
  player/            controller, survival stats, skills/traits/occupations, first-person viewmodel
  items/             item database, inventory, loot tables, crafting recipes
  town/              settlement simulation: residents, homes, jobs, needs, happiness, newcomers
  factory/           machine/recipe/settlement-level definitions and the factory simulation (belts, power, turrets)
  build/             player structures and the holographic placement system
  models/            procedural 3D models: weapons, props, cars, trees, machines, town buildings, trains, build pieces
  audio/             procedural Web Audio engine: SFX, loops, ambience, adaptive music
  ui/                DOM HUD, menus and panels
preview/             dev galleries for textures, models, machines and audio (npx vite --config preview/vite.config.ts)
```
