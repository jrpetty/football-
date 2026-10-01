# Holdout v3: design analysis

This document is the plan for taking Holdout from a solid prototype to a deep,
long-running game. It starts with what the game is today, looks at the games it
draws on, and then sets out each system, why it is there, and how it works.

## 1. Where v2 stands

**Working well**
- **The camp loop:** 20 stations, jobs, crafting queues, quality tiers, mods,
  repairs, power and automation, expansions, live horde fights.
- **The run loop:** real building interiors, room-typed loot, search versus
  break-down, noise, the horde timer and extraction.
- **Looks:** lofted skinned characters and HD props.

**What holds it back**
1. **No long spine.** A headless simulation shows a competent player reaching
   about 28 survivors and nearly every station at level 3 by day 25. That is
   about 3.5 hours of play. After that there is nothing left to aim for.
2. **Shallow production.** Resources teleport into one global pool. Nothing
   needs planning: no layout, no throughput, no bottlenecks. The
   "Satisfactory" feeling, a growing machine you tune, is missing.
3. **Runs are fully visible.** You see every zombie and every room the moment
   you arrive. Nothing is unknown, so there is no reason to scout and no
   surprise.
4. **Survivors are interchangeable** apart from job efficiency. Nobody is the
   squad's eyes, the medic or the tank.
5. **Difficulty grows with day count**, not with what the camp has achieved.
   That breaks as soon as play lasts weeks.

## 2. What the reference games teach

| Game | What it does | What we take |
| --- | --- | --- |
| *The Last Stand: Dead Zone* | Five classes: Fighter, Scavenger, Engineer, Medic, Recon. Recon spots traps; Engineer disarms them. Injuries are minor or severe and heal over real time. A research bench. 7-minute missions with a hard exit timer. | Survivor roles with abilities, traps, real injuries, a research station. |
| *Satisfactory* | Ten milestone tiers at the HUB, gated by Space Elevator phases. Hard drives give a choice of alternate recipes. Power shards overclock machines to 250%. Belts from 60 to 1200 items/min, with splitters and mergers. Everything is shown as a rate. | Tiered milestones, a multi-phase capstone project, schematics that unlock alternate recipes, overclock cores, belts with throughput, rates everywhere. |
| *State of Decay 2* | Outposts claimed across the map give daily resources and can be upgraded; water and power gate facility upgrades; legacy goals form the endgame. | City outposts that produce, need defending, and feed the camp by convoy. A clear-the-city endgame. |
| *Project Zomboid* | Eagle Eyed, Short Sighted, Keen Hearing, Hard of Hearing and Cat's Eyes traits. Panic narrows vision. The visible area is shaded by lighting and line of sight. | Perception as a real stat: sight range, hearing range, night sight, and a scout who can see through a wall. |
| Fog-of-war renderers (Civilization VI, RTS games) | Three states: unexplored, explored but not currently seen, and visible. A low-resolution visibility texture is computed on the CPU, softened and eased over time, and applied in a shader. | The same three-state model at 25 cm resolution, eased over time and applied as a full-screen pass. |

## 3. Pillars

1. **You only see what your people see.** Runs become scouting, tension and ambush.
2. **The camp is a machine you design.** Belts, rates, buffers and alternate
   recipes reward planning and layout.
3. **Every survivor is somebody.** Perception, roles, perks, injuries, infection.
4. **A horizon measured in months.** Tiers, a capstone project, a city to
   reclaim, seasons, research, survivor careers. Threat scales with what the
   camp has achieved, not with the calendar.

## 4. Systems

### 4.1 The vision engine (supply runs)
- **Three states per 25 cm cell:**
  - *Visible:* full colour; zombies are shown.
  - *Explored:* a cold, desaturated memory; furniture and loot marks stay, zombies vanish.
  - *Unexplored:* drifting dark fog.
- **Line of sight:** each survivor casts about 360 rays a tick through the tile
  grid. Walls, neighbouring buildings, the van and tall shelving block sight.
  Sight is fullest in a ±70° forward cone and about 65% behind.
- **Room reveal:** stepping into a room maps the whole room (its walls,
  furniture and containers). Zombies still need direct line of sight.
- **Wall sense:** scouts see through one wall within a few metres. Those cells
  show in a cyan scan tint, and zombies there appear as x-ray silhouettes
  through the wall.
- **Hearing:** moving or noisy zombies within hearing range leave sound
  ripples at their rough position, even through walls.
- **Last seen:** a zombie that slips out of sight leaves a fading marker where
  it was last seen.
- **Night:** sight shrinks to about 45%. A flashlight adds a forward cone,
  night-vision goggles cancel the penalty, fog cuts sight and rain cuts
  hearing.
- **Perception stats:**
  - *Sight* (metres): Eagle-Eyed adds, Nearsighted subtracts.
  - *Hearing* (metres): Keen Hearing adds, Hard of Hearing subtracts.
  - *Night sight*: Cat's Eyes improves it, Night-Blind worsens it.
  - *Wall sense*: from the new Scout job, the Sixth Sense trait, or crafted
    Thermal Goggles.
  - Occupations shift these too (Hunter and Guard see further).
- **Renderer:** visibility lives on the CPU in a tile grid. It is upsampled
  4× with wall-side awareness so nothing leaks through walls, lightly blurred,
  and eased over time. A full-screen effect rebuilds world position from
  depth, samples the texture and shades each state. Explored maps persist
  per location between visits.
- **Minimap:** shows the explored layout, current sight, the squad, seen
  zombies, sound pings, containers and the van. Clicking it moves the camera.
- **New threats that use the dark:**
  - *Stalkers* hunt in the fog.
  - *Screamers* wake everything when they see you.
  - *Bloaters* burst into a toxic cloud.
  - *Traps* (tripwires, bear traps, shotgun traps) stay hidden until somebody
    spots them; scouts spot them from much further away.

### 4.2 Production with Satisfactory DNA
- **Rates everywhere:** every flow is shown per real minute.
- **Belts:**
  - Connect a station's output to another station or to storage. The route
    is laid automatically along free tiles, and the belt costs scrap and metal
    per tile, so compact layouts pay.
  - Items visibly ride the belts.
  - Belts come in three tiers: Mk1 30/min, Mk2 60/min, Mk3 120/min.
  - One output can feed several belts. Items go round-robin, with
    back-pressure when buffers fill.
- **Hand hauling:** stations without belts lose time carrying goods by hand
  and run at 70%. A belted station runs at 100%. Base rates are raised so
  that an unbelted camp plays like v2, which makes belts a pure upgrade.
- **Components ladder:**
  - Raw goods: scrap, wood, water, crops, fuel, chemicals.
  - Refined goods: metal, cloth, parts, gunpowder.
  - Components: steel, wiring, circuits, motors, rubber.
  - Project parts: radio arrays, power cells, signal amplifiers.
  - A new **Machine Shop** is the assembler tier.
- **Milestones and tiers:**
  - The campfire becomes the Command Post. Its milestone board has tiers 0
    to 8, and deliveries unlock stations, station levels 4 and 5, belt tiers,
    recipes and expansions.
  - **The Signal** is a five-phase capstone: restore Ashford's broadcast
    mast and call the coastal evacuation. Each phase asks for more advanced
    components and unlocks the next tiers.
- **Research desk:**
  - *Schematics* (found on runs, like Satisfactory's hard drives) offer a
    choice of one of three alternate recipes.
  - *Specimens* from special infected drive infection research (antivirals,
    then immunity).
  - Research runs on real-time timers.
- **Power cores:** rare finds that overclock an automated station by 50%
  each, up to 250%. Power draw rises faster than output.
- **Factory statistics:** production and consumption per resource, a rolling
  history graph, a bottleneck list, and the supply-chain view.

### 4.3 Survival depth
- **Infection:** a bite can infect, with armor lowering the chance. Infection
  climbs over two to three days; infirmary care slows it. An antiviral cures
  it. If it reaches 100%, the survivor turns inside the camp.
- **Injuries:** minor or severe, with stat penalties and healing time.
- **Seasons:** each lasts six days.
  - *Spring:* rain.
  - *Summer:* everyone drinks more.
  - *Autumn:* a harvest bonus.
  - *Winter:* farms and collectors stop unless greenhouses or heaters run, the
    camp burns fuel or wood for heat, and snow covers the ground.
- **Survivor careers:** skills cap at 15. Every 5 levels a survivor chooses a
  perk (Medic, Scout, Gunner, Brawler, Engineer and Scavenger lines).

### 4.4 The city as an endgame
- **Districts:** each district carries an infestation level. Runs and
  clearing missions lower it; it creeps back slowly.
- **Nests:** each district hides one or more nests that send hordes. Burning
  one is a dedicated objective on a run.
- **Outposts:**
  - A location you have cleared can be claimed as an outpost: a gas station
    pumps fuel, a supermarket supplies food, a hospital meds, the hardware
    store scrap and metal, the water tower water.
  - Each outpost needs a garrison of one or two survivors. It can be
    upgraded twice and comes under occasional attack.
  - Convoys bring its goods home, and trucks visibly drive the roads on the
    city map.

### 4.5 Pacing for months
- **Threat level:** a blend of camp tier, population, wealth and a slow,
  capped share of days drives horde size and special-infected mix. Every
  seventh night is a **Blood Moon** horde.
- **Long goals:**
  - Milestone costs roughly double each tier, and the Signal phases ask for
    hundreds, then thousands, of components.
  - Reclaiming every district takes dozens of runs.
  - Survivor perks, the schematics collection and research keep paying off
    for weeks.
- **Offline progress:** the camp keeps working at a reduced pace while the
  game is closed, capped so time away never replaces playing. Any horde that
  hits meanwhile is resolved automatically and reported on return.
- **Saves:** export and import a save file, and keep a rolling backup, so
  months of progress are never at the mercy of one browser's storage.

## 5. Build order
1. Vision engine, perception stats and minimap; then traps and the new infected.
2. The components ladder, Machine Shop, milestones and tiers, and the Signal
   project.
3. Belts and logistics, rates, factory statistics, overclocking, research and
   schematics.
4. Infection, seasons, perks, threat scaling, outposts and convoys, offline
   progress, save export and import.
5. A graphics pass (fog look, seasons, belts, lighting, effects), UI for every
   system, an in-game field manual, long simulations for pacing, then ship.
