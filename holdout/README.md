# Holdout

A zombie-survival camp builder in the spirit of the old Facebook game
*The Last Stand: Dead Zone*. You run a fenced camp seen from a 45° camera, give
every survivor a job, chain workstations into supply lines, send squads into a
3D city to loot buildings shelf by shelf, and hold the wall when the horde
comes. The goal is to last as long as you can.

**Play:** open [`../holdout.html`](../holdout.html) in a desktop browser. It is
one self-contained file (three.js and the post-processing stack are bundled in)
that runs offline and saves progress in that browser. It is built for PC first:
mouse and keyboard, a 1600×900 or larger window, and a dedicated GPU for the
High and Ultra presets. Settings also has Low and Medium for weaker machines.

## Controls

| Where | Input | Does |
| --- | --- | --- |
| Everywhere | Left-drag, WASD / arrows, screen edges | Pan (hold Shift to pan faster) |
| | Scroll, `+` / `-` | Zoom |
| | Q / E, middle-drag (right-drag in camp and on the map) | Rotate the camera |
| Camp | `B` `C` `I` `T` `P` `M` `G` `L` | Build, Crew, Items, Trade, Camp overview, Map, Goals, Log |
| | `F` / `H` | The wall / the next horde |
| | `Space`, `1` `2` `3` | Pause, 1×, 2×, 4× speed |
| | `R`, `Shift`, `Enter`, `Esc` | While placing: rotate, place several, confirm, cancel |
| Supply run | Left-click, drag a box, `1`–`4`, `Tab` | Select survivors (double-tap a number to jump to them) |
| | Right-click | Move, attack a zombie, or do the default action on a container |
| | Left-click a container | Menu: search it (quiet) or break it down for materials (loud) |
| | `Z` `X` `C` `V` | Molotov, pipe bomb, noise maker, first aid kit |
| | `Enter`, `Space`, `F` | Extract at the van, pause, focus the selection |

## How it plays

### The camp
- You start in an old lumber yard with four survivors, a campfire, a tent
  bunkhouse, a farm plot, a rain collector, a storage depot and a workbench.
- Every survivor eats about 2 food and drinks 2.4 water a day. The Camp overview
  (`P`) shows every resource with its storage cap and its net change per day.
  If food or water runs out, everyone weakens and works at 60%.
- Survivors without a job build whatever is under construction. When nothing is
  going up, they forage the yard for a little wood and scrap.
- Morale (60 to start) comes from beds, hot meals, the fire, the wall and recent
  events. High morale makes everyone work up to 20% faster. Below 18, people
  start leaving in the night.
- A **horde timer** sits at the top of the screen. Hordes grow with the days and
  the size of the camp. A Watchtower reveals how big the next one is.

### Base expansions
The fence starts tight. Each side can be pushed out twice, eight expansions in
all: woodlot, wrecking lot, meadow and loading yard first, then pine ridge,
freight yard, orchard and roadside strip. Each one costs materials and cash,
takes a crew time to clear, and pays back salvage (timber, scrap from wrecks,
sealed containers, fruit). The wall moves out at its current level. The camp
can also be walled with a log palisade, sheet metal or a fortified wall, priced
per 10 m of perimeter.

### Survivors
- **24 pre-outbreak jobs** set starting skills and give a perk: Doctor, Nurse,
  Paramedic, Police Officer, Soldier, Firefighter, Farmer, Chef, Carpenter,
  Mechanic, Electrician, Engineer, Tailor, Gunsmith, Hunter, Athlete, Student,
  Teacher, Plumber, Construction Worker, Store Clerk, Ex-Con, Drifter and
  Security Guard. Most jobs are best at one or two stations.
- **Eight skills** level up with use: Melee, Ranged, Scavenging, Building,
  Crafting, Survival, Medicine and Engineering.
- **Traits** add strengths and flaws, such as Tough, Quick, Steady Hands,
  Glutton, Clumsy or Coward.
- **Equipment:** a weapon, armor and one gear item each, plus utility items for
  runs. Items have a quality tier (Crude, Standard, Fine, Masterwork), a
  condition that wears down with use, and one mod slot.
- **Death is permanent.** The dead are listed in the Memorial.

### Workstations and what each one is for
Every station has a clear job in the supply chain. Most have three levels, and
higher levels add worker slots, speed and recipes.

| Group | Station | Purpose |
| --- | --- | --- |
| Camp | Bunkhouse | Beds. Newcomers can only join if there is a free bed. |
| | Storage Depot | Raises the cap on every resource. |
| | Cookhouse | A cook stretches food 15–30% further and lifts morale. Burns wood. |
| | Infirmary | Heals the injured first; between patients, makes first aid kits and, at level 2, medicine. |
| | Training Yard | Trainees drill melee or ranged. Teachers speed everyone up. |
| | Radio Tower | More and better recruits, more distress calls, a better-stocked trader. |
| Production | Farm Plot | Water → food. |
| | Rain Collector | Free water, double in the rain. |
| | Water Filter | Water from the well. |
| | Lumber Yard / Scrap Yard | Wood / scrap, with the odd part or circuit board from wrecks. |
| | Forge | 3 scrap + 1 wood → 2 metal, with a stock limit so it does not eat everything. |
| | Biofuel Still | 2 food + 1 water → fuel for the generator and the van. |
| | Generator / Solar Array | Power for automated stations, turrets and floodlights. |
| Crafting | Workbench | Parts, melee weapons, lockpicks and toolkits; melee mods and repairs. |
| | Weapons Station | Pistols to assault rifles; suppressors, scopes and magazines; gun repairs. |
| | Ammo Press | Metal + gunpowder → 9mm, 7.62 or 12-gauge. Pick a calibre, or let it top up whichever is lowest. |
| | Tailor Station | Jackets, kevlar, ghillie, riot and combat armor, backpacks; armor mods and repairs. |
| | Electronics Bench | Flashlights, noise makers, walkie-talkies, night vision and automation modules. |
| | Chemistry Lab | Fuel + scrap → chemicals → gunpowder; molotovs and pipe bombs. |
| Defense | Watchtower | A guard fires with bonus damage, picks off night wanderers and reveals horde size. |
| | Auto-Turret | Fires at the fence on generator power and pistol ammo. |
| | Floodlight | Defenders nearby shoot at full accuracy at night. |

**Crafting benches** work through an order queue. Each order can run once,
repeat N times, or "keep stocked" (pause once you hold enough). Materials are
paid when a unit starts. Benches also keep the crew's gear repaired: anything
under 60% gets fixed between orders. The chance of Fine or Masterwork results
rises with the crafter's skill, a Gunsmith or Tailor at their bench, Steady
Hands and the bench level.

**Automation** (the Satisfactory part): give a station of the right level an
Automation Module from the Electronics Bench, power it, and it runs with nobody
assigned. A full ammo line is Scrap Yard → Forge (metal), Still → Chemistry Lab
(chemicals → gunpowder) → Ammo Press, all on one generator, with the Still
feeding the generator its fuel.

### Supply runs
- The city of Ashford has 33 locations in five danger levels, laid out over
  residential streets, a commercial strip, downtown towers, an industrial
  district, a river and a rail line. Houses and corner stores are near camp; the
  police station and the military checkpoint on the highway are far out.
- Pick a squad of up to four. Each survivor brings their own weapon, armor,
  gear and utility items. The van burns fuel for the round trip; without fuel
  the squad walks, which takes three times as long.
- Each run builds the real building on its lot: typed rooms (kitchens, wards,
  armories, sales floors with aisles), doors, windows and furniture, plus the
  yard, the street and the neighbours. Every container has a loot table that
  fits it: fridges hold food, gun lockers hold guns, server racks hold
  electronics.
- Click a survivor, then a container, to search it (quiet) or break it apart for
  materials (loud). Survivors hold the spot you put them on and fight anything
  that comes close. A lone survivor gets swarmed.
- Noise draws zombies: gunshots carry far, crossbows and Quiet survivors don't.
  Safes and gun lockers are locked; open them with lockpicks or an Ex-Con, or
  smash them open and make a racket.
- Packs fill up. Carry loot to the van to bank it. A horde timer runs on every
  run, so extract before you are overrun.
- A downed survivor bleeds out in 40 seconds unless a teammate revives them.
- The camp clock keeps running (slower) while you are out. A horde can hit the
  camp while you are away, and the people you left behind fight it on their own.

### Economy
- The **black market** sells weapons, armor, gear and resource bundles, and
  buys anything. Its stock changes at dawn, and a Radio Tower improves it.
- **Distress calls and supply drops** appear on the city map for a while.
  Rescued survivors join the camp.
- **Goals** guide the early game and pay rewards.

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
  main.js            boot, title screen, scene switching, main loop
  game/
    data.js          every definition: resources, jobs, skills, items, mods,
                     stations, recipes, expansions, locations, zombies, goals
    state.js         game state, survivors, items, orders, saving
    economy.js       the camp simulation: production, crafting, power,
                     morale, recruits, hordes, the market
  scenes/
    base*.js         the camp: stations, people, fence, horde fights
    citymap.js       the 3D city map and squad planner
    mission.js       supply runs: building interiors, looting, extraction
  world/
    city.js          seeded city layout: streets, districts, lots, locations
    levelgen.js      supply-run levels: floor plans, rooms, furniture, loot
    agents.js        survivor and zombie AI and combat
  models/            procedural models: lofted skinned characters (body.js,
                     dress.js), stations, furniture, weapons, vehicles, city
  render/            pipeline (SSAO, bloom, SMAA, tilt-shift), sky, terrain,
                     procedural textures and materials, effects
  ui/                HUD, drawer panels, station, crew and map panels
  core/              audio, A* grid, utilities
```
