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

## 6. As built

Everything in sections 4.1 to 4.5 shipped except where noted below. These are
the places where the shipped game differs from the plan, and why.

### Production
- **Rates are per camp day, not per minute.** A camp day is eight real
  minutes, and daily figures match how the rest of the camp is read (food per
  day, water per day). The belts carry 120, 320 and 800 items a day.
- **No hand-hauling penalty.** Slowing every unbelted station to 70% made the
  early game feel worse without teaching anything. Instead a station gets
  +15% for each side that is fully belted, so belts are a pure reward. A
  station belted to a consumer also runs **on demand**: it makes what the next
  station needs. That is the part of Satisfactory that makes a line feel
  designed.
- **Routing:** a Dijkstra search over (tile, heading). A corner costs as much
  as 2.5 m of belt and crossing another belt costs 4 m, so belts run straight
  and only cross when they must. Every belt runs on posts above head height,
  and where two cross, the newer one runs a layer higher. Belts cost per tile, so compact layouts still pay.
- **The milestone board is the Progress panel**, not a Command Post at the
  campfire. Deliveries come straight from storage. A building you have to walk
  to added nothing.
- **No station levels 4 and 5.** Tiers unlock new stations, belts, walls,
  land, recipes and camp-wide bonuses instead. Extra levels on every station
  would have meant re-balancing all 29 of them.
- **The Machine Shop shipped** (internally `assembler`), with the Fabricator
  as a second component station for parts, wiring and electronics.
- **Power cores:** up to three per station, +50% each (+75% after Core
  Tuning), for 250% to 325% speed. Power draw scales with speed to the power
  1.5.

### Survival
- **Injuries** stayed as one "injured" state with healing over time. Minor
  and severe injuries were cut: infection already makes a bite matter.
- **Infection** climbs 40 points a day, so about two and a half days from bite
  to turning. Fever (30) costs work speed and health, turning (70) halves work
  and grounds the survivor. An antiviral cures below 60.
- **Winter** has no greenhouses or heaters. Farms drop to 45% and collectors to
  55%, and the camp burns wood (or fuel) for heat. A freezing camp loses morale
  and health, so autumn stockpiling is the plan.
- **Perks** are two choices per skill at levels 5, 10 and 15, rather than class
  lines. Any survivor can grow in any direction they work in.

### The city
- **Districts, infestation levels and nests were cut.** Threat already scales
  with the camp, and a second difficulty dial on the map made the two hard to
  read together. The Signal is the endgame instead.
- **Outposts** can be claimed on any location you have run (not one you have
  cleared), held by one to three survivors, and upgraded twice. Convoy trucks
  shuttle between camp and each outpost on the city map.

### Pacing
- **Threat** is `tier × 1.3 + Signal phase + population / 8 + expansions × 0.4
  + min(day, 100) / 20`. A Blood Moon replaces any horde due within 20 hours of
  it, so the two never stack.
- **Offline progress** gives one fifteenth of the time away, capped at three
  camp days, and **holds the clock**: no hordes, no day rollover and no events
  while the game is closed. Resolving hordes automatically while the player
  could not respond felt like punishment for closing the game.
- **The finale** is a scripted last horde after Signal phase 5. Holding it
  wins the game.

### Measured pacing
A scripted player in a headless simulation of the real economy code, run for
250 to 357 days across several seeds:

| Mark | Day |
| --- | --- |
| Tier 2 | about 20 |
| Signal phase 1 | 28 to 33 |
| Signal phase 2 | 54 to 66 |
| Signal phase 3 | 146 to 195 |
| Tier 7 | 176 to 300 |
| Tier 8 | from about 277 |

The camp survived past day 290 in every seed. The runs also exposed five
balance problems, all fixed before shipping:
- A Blood Moon could stack on a normal horde the same night.
- Injured survivors never recovered while the camp was hungry.
- The auto-resolved horde grew with the day count instead of the threat.
- Winter starved camps that had not stockpiled.
- Mid-game threat outpaced the walls. Sheet metal moved from tier 4 to tier 3.

### Graphics
- The fog of war, wall-sense tint and x-ray silhouettes follow section 4.1.
- Snow builds up on roofs, terrain and grass in winter, with falling flakes.
  It stays out of interiors on runs.
- Broadleaf trees turn gold and red through late summer and autumn and go
  brown under the winter snow.
- Blood Moons tint the whole grade red.
- The Signal Mast grows through six visible stages, with a beacon that turns
  on at the top.
- Belts carry instanced cargo with per-tier frames and status lamps.

## 7. v4: depth, the road and friends

v4 answers four requests: make building feel like an industry (every station
made of materials you smelt and shape, power you have to generate), make the
city cost something to cross, give the long game a story, and let friends play
one camp together in the browser.

### Materials
The Satisfactory lesson is that a factory feels earned when every part has a
lineage. v3 had components, but stations were still built from raw scrap and
wood. v4 adds a ladder in between:
- scrap is smelted into **metal bars** (Forge), wood is baked into **coal**
  (Charcoal Kiln);
- bars are shaped into **plates** and **bolts**, by hand at the Workbench or by
  the powered Fabricator; bars and coal make **steel** (Forge level 2); steel
  and bolts make **beams** (Workbench level 2, Machine Shop);
- level 1 of every station costs simple materials, so a new camp is never
  stuck; every upgrade, the walls, the mast and the top tiers need plates,
  bolts and beams.

Hand crafting and machines share recipes. A machine without power is worked
by hand at half speed: power is a multiplier, never a lock.

### Power
Fuel engines first, renewables later, storage last, the arc every factory game
uses because it teaches the grid a piece at a time:
- tier 1 **Steam Engine**: wood, or coal at three times the burn time, and only
  as much fuel as the load needs;
- tier 3 **Generator** on diesel;
- **Solar** and (tier 5) **Wind**, free but weather-bound;
- (tier 6) **Battery Bank**, which only charges from renewables, so it rewards
  building them.
When supply runs short, defence is served first, then staffed machines, then
automation. The Power panel shows every source, the fuel mix and every user.

### Upkeep
Upkeep exists to keep runs worth taking for a camp that is already
self-sufficient in food and water. It scales with the camp (cloth and meds per
person, scrap per station level, bolts for upgrades, parts for machines) and
is paid continuously. Missing it costs 15% work speed and some morale, never
lives. After the first simulation pass the per-level rates were cut (scrap
0.25 to 0.15, bolts 0.6 to 0.35) because big camps spent most of their scrap
on patching.

### The road
Distance is the city's real currency. Provisions per person for a round trip
follow `0.3 + 2·km + 8·km²`, so next door is almost free and the far side of
the map on foot is a serious decision. Vehicles cut provisions to a quarter or
a third and burn fuel by the kilometre. The van starts dead and the parts to
fix it come from wrecks on runs, so the first vehicle is a small quest. Cars
can be found running, keyed or hotwired. The vehicle also decides the haul:
on foot you bring home what you carry.

### The story
Long camps need reasons beyond numbers. Five threads (the van, a radio
engineer, the company that made the virus, the coast's codes, a lost convoy)
are told through notes and people found on runs. Clues narrow a thread to a
few places rather than marking one, so following a lead is still a choice of
where to risk a run. Two Signal phases are gated on the story, each with a
slower fallback (research) or none (the codebook), which ties the end of the
factory game to the city.

### Multiplayer
Requirements: browser only, invite and join, a host's save separate from
single player, survivors assigned to players, everyone in the same live camp.

**Authority.** The host's browser runs the simulation and keeps the save.
Guests never run the economy. This avoids divergence in a simulation full of
randomness and keeps cheating out of the question.

**State sync.** The camp state is one JSON tree. The host diffs it against
what guests last received a few times a second. The diff format knows keyed
records (survivors, stations, items, belts, log lines by id), patches records
in place so scene objects keep their identity, rounds numbers to what is worth
sending and ignores drift below a small tolerance. Belt cargo positions and
history graphs travel on slower channels of their own. A joining guest gets
one compressed snapshot, then numbered diffs; a gap triggers a resync.

**Guest actions.** Guests keep the existing UI code unchanged: every panel
mutates the local state as in single player. A periodic diff of that state
against the last confirmed one becomes a patch for the host. Resource changes
travel as amounts to add, so two players spending at once both pay. The host
checks each patch (survivors you do not lead are refused, spending beyond
storage is refused), applies it and acknowledges it in its next diff. The
guest keeps unacknowledged patches on top of every diff from the host, so its
screen never flickers back, and a refusal simply drops the patch.

**Runs and raids.** Runs are local to the player who launched them; what they
bring home arrives as an ordinary patch. Raids are fought by the host's
simulation and streamed to guests as zombie and defender positions at five
frames a second, rendered with puppets that interpolate between frames. Guests
can order the defenders they lead; the host's game carries the orders out.

**Runs together.** A co-op run is played on the leader's machine, as a solo
run is; nothing about the mission simulation changes. Friends build the same
street: the layout is already seeded by the lot, and the run's own seed now
drives the dice for loot and zombies, so everyone gets the same containers in
the same places. What the leader's game still rolls on its own (traps, the
caller, which containers hold story items or need keys, the cars) travels once
as a setup message. After that the leader streams a frame about eight times a
second: every survivor and zombie, containers and traps whose state changed,
and the moments worth seeing (shots, blood, fire, throws, floating text, a few
sounds), which friends' games replay on puppets. Friends' orders go to the
leader as small messages, are checked against who leads that survivor, and
are carried out there. Messages between players always pass through the host,
which also notes which survivors a run leader answers for while out, so the
results of the run (statuses, loot, injuries) are accepted when the leader's
game reports them.

**Always-on camps.** The same game code runs on a Node server in a worker
thread with the renderer stubbed out, acting as the host. While players are
on, it ticks in real time; with nobody on, it unloads, and on the next load it
catches up the way a single-player camp does on return (slow pace, clock
stopped, capped at three days). A horde cannot be fought by numbers alone when
players are watching, so the server hands it to a player in camp (the raid
captain), whose game runs the fight and streams it, sending the result back.
If the captain drops, the rest is resolved by the numbers.

**Transports.** One interface over four carriers. Inside claude.ai the
artifact viewer's live room: a named room per camp, the lobby room's presence
lists open camps, and guests without permission to send events fall back to a
presence mailbox the host acknowledges. The standalone file uses WebRTC data
channels through PeerJS. On the Holdout website, a WebSocket to its server,
which relays between browsers and hosts the always-on camps. BroadcastChannel
connects tabs for testing. Frames
are JSON; large messages are deflated, base64'd and split to fit the room's
4 KiB limit, and paced under its rate budget.

### Measured pacing (v4)
The v3 scripted player, updated to pay travel provisions, repair the van,
craft plates, bolts and beams, follow story leads and research its way past
the dish array, run for 300 days on several seeds:

| Mark | v3 | v4 |
| --- | --- | --- |
| Van running | | 9 to 20 |
| Tier 2 | about 20 | 37 to 51 |
| Signal phase 1 | 28 to 33 | 51 to 53 |
| Signal phase 2 | 54 to 66 | 74 to 90 |
| Signal phase 3 | 146 to 195 | 240 to 270 |

Every seed survived past day 400 with 30+ survivors. A thoughtful human
moves faster than this script, which never lays belts, so the real arc should
land between the two columns. The first passes exposed four problems, all
fixed:
- Once a Blood Moon broke through, every defender was injured, every
  following horde broke through too, and the camp starved. Injured survivors
  now still defend at a fraction, a breach buys a day and a half before the
  next horde, breaches take 15 to 35% of the stores instead of up to half,
  and the first Blood Moon comes on day 21.
- Kilns, stills and steam engines burned the last wood and food the people
  needed. Factories now leave a few days of food, water and (near winter)
  firewood untouched, and say so on the station.
- Upkeep on a big camp ate most of its scrap. The per-level rates were cut.
- The script never ordered beams; a human sees the Signal's cost and will.
