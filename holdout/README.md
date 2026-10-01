# Holdout

A zombie-survival camp builder in the spirit of the old Facebook game
*The Last Stand: Dead Zone*. You run a fenced camp, give every survivor a job,
send squads into the city to loot buildings, and hold the wall when the horde
comes.

**Play:** open [`../holdout.html`](../holdout.html) in any modern browser. It is
one self-contained file (three.js is bundled in), works offline, and saves
progress in that browser.

## How it plays

### The camp
- Seen from a 45° camera. Drag to pan, scroll or pinch to zoom, right-drag, Q/E
  or two fingers to rotate.
- You start with four survivors, a campfire, a tent bunkhouse, a farm plot, a rain
  collector, a storage depot and a workbench.
- Every survivor eats about 2 food and drinks 2.4 water a day. If either runs out,
  everyone loses health and works at 60%.
- Survivors without a job help build. A carpenter or construction worker builds
  much faster. At night, idle survivors sit around the fire.
- A **horde timer** sits at the top of the screen. Hordes grow bigger as the days
  go on. With a Watchtower you can see how big the next one is.

### Survivors
- **Pre-outbreak jobs** set each survivor's starting skills and give them a perk.
  There are 24, including Doctor, Nurse, Soldier, Police Officer, Firefighter,
  Farmer, Chef, Carpenter, Mechanic, Electrician, Engineer, Tailor, Gunsmith,
  Hunter, Teacher, Plumber and Ex-Con.
- **Eight skills** level up with use: Melee, Ranged, Scavenging, Building,
  Crafting, Survival, Medicine and Engineering.
- **Traits** add strengths and flaws: Tough, Quick, Sharpshooter, Quiet, Glutton,
  Clumsy, Coward and so on.
- **Equipment** comes in three slots: weapon, armor and gear. Gear includes
  backpacks, flashlights, lockpicks, first aid kits and walkie-talkies.
- A survivor's job efficiency depends on their skill, job and traits. A farmer on
  the Farm Plot or a gunsmith at the Weapons Station can be twice as productive as
  anyone else.
- **Death is permanent.** The dead are listed in the Memorial.

### Workstations (20)
| Group | Stations |
| --- | --- |
| Camp | Bunkhouse (beds), Storage Depot, Cookhouse (camp eats less), Infirmary (heals, makes meds), Training Yard (drills combat skills), Radio Tower (more and better recruits) |
| Production | Farm Plot, Rain Collector, Water Filter, Lumber Yard, Scrap Yard, Biofuel Still, Generator |
| Crafting | Workbench, Weapons Station, Clothing Station, Ammo Press (each crafting station has a queue) |
| Defense | Watchtower, Auto-Turret, Floodlight, plus four perimeter wall upgrades |

Most stations have three levels. At level 2 or 3, many stations can be
**automated**: they run on generator power without anyone assigned. You fuel the
Generator from the Biofuel Still, which turns food and water into fuel.

### Supply runs
- The city map has about 34 locations in five danger levels. Houses and corner
  stores are close to camp. Police stations and military checkpoints are far away.
- Pick a squad of up to four. Click a survivor, then click the ground to move them,
  a container to **search** it (quiet) or **break it apart** for materials (loud),
  or a zombie to attack it.
- Survivors hold the spot you put them on and fight anything that comes close. A
  lone survivor gets swarmed.
- Noise draws zombies. Gunshots carry far, but crossbows and Quiet survivors don't.
- Gun lockers and safes are locked. Open them with lockpicks or an Ex-Con, or smash
  them open and make a lot of noise.
- A horde timer runs on every run. Get everyone back to the van and extract before
  you are overrun.
- A downed survivor bleeds out in 40 seconds unless a teammate revives them.
- While a squad is out, the camp clock keeps running. A horde can hit the camp
  while you are away, and the people you left behind fight it on their own.

### Economy
- The **black market** sells weapons, armor, gear and resource bundles, and buys
  anything. Its stock changes at dawn.
- **Goals** guide the early game and pay rewards.

## Development

```bash
cd holdout
npm install
npm run build        # writes ../holdout.html
```

The code is plain ES modules in `src/`, and `build.mjs` bundles them with
esbuild into one HTML file:

```
src/
  main.js       boot, title screen, scene switching, main loop
  state.js      game state, survivors, economy tick, hordes, recruits, saving
  data.js       every definition: jobs, skills, items, stations, recipes, locations
  base.js       the camp scene: stations, workers, placement, horde attacks
  mission.js    supply runs: level generation, looting, extraction
  agents.js     survivor and zombie AI and combat
  worldmap.js   the city map and squad planner
  ui.js         HUD, panels and modals
  models.js     procedural low-poly models (people, zombies, props, stations)
  gfx.js        renderer, camera, input, day/night lighting, effects, labels
  grid.js       A* pathfinding and line of sight
  audio.js      procedural WebAudio sound effects
```
