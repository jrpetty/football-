# Holdout

A zombie-survival camp builder in the spirit of the old Facebook game
*The Last Stand: Dead Zone*, with a factory at its heart that owes a lot to
*Satisfactory*. You run a fenced camp seen from a 45° camera, give every
survivor a job, belt stations together into production lines, and send squads
into a 3D city where they only see what they can actually see. Hordes grow with
what you build. The long goal is to rebuild a broadcast mast in five phases,
call the coast for an evacuation, and hold out through the last night.

v4 adds a full materials ladder (scrap to bars to plates, bolts, steel and
beams, by hand or by machine), a real power grid (steam engines on wood and
coal, diesel, solar, wind and batteries), camp upkeep, travel costs and a
motor pool, a story told through notes, leads and people found in the city,
gear swapping in camp, and **browser multiplayer**: one player hosts, friends
join with a code, and each of you leads your own survivors in the same camp.

v5 adds priority splitters and buffer hoppers on belt lines, storage in
three sizes whose piles fill up as stock rises, a Recycler, chickens and goats,
off-grid power (pedal generators, salvaged solar panels and solar lamps, a
biogas digester fed by the animals),
survivor diaries, barricades on runs, recon scouts and a roaming horde, and a
set of tools for running a big camp: a command palette (`Ctrl`+`K`), a flow
chart for every resource, a watch list, custom alerts, a go-to-problem key and
mass actions on several stations at once.

It is built to be played over weeks and months. A camp day lasts about eight
real minutes at normal speed, the milestone board runs to tier 8, and a careful
player reaches the final Signal phase somewhere past day 250.

**Play:** open [`../holdout.html`](../holdout.html) in a desktop browser. It is
one self-contained file (three.js and the post-processing stack are bundled in)
that runs offline and saves progress in that browser. It is built for PC first:
mouse and keyboard, a 1600×900 or larger window, and a dedicated GPU for the
High and Ultra presets. Settings also has Low and Medium for weaker machines.
Press `F1` in the game for the field manual.

**Play together:** press **Multiplayer** on the title screen. A multiplayer
camp is saved apart from your single-player camp. One player hosts; friends
join with the five-letter code, or pick the camp from the list. Each of you
leads your own survivors, and you can head out on **supply runs together**:
one street, everyone giving orders to their own people. On the Holdout
website (the server in `server/`, ready for fly.io) you can also start an
**always-on camp** that lives on the server, so nobody has to host and friends
come and go as they like. Inside Claude, friends open the same artifact and
pick your camp from the list (invite them from the share menu). With the
standalone file, the browsers connect directly over WebRTC through the free
PeerJS broker; most home connections work, a few strict office or mobile
networks don't.

## Controls

| Where | Input | Does |
| --- | --- | --- |
| Everywhere | Left-drag, WASD / arrows, screen edges | Pan (hold Shift to pan faster) |
| | Scroll, `+` / `-` | Zoom |
| | Q / E, middle-drag (right-drag in camp and on the map) | Rotate the camera |
| | `F1` | Field manual (pauses a run) |
| | `Alt`+`Enter`, the button above the menu | Full screen (hold `Esc` to leave) |
| | `F3` | FPS counter on or off (also in Settings) |
| Camp | `B` `C` `I` `T` `P` `M` `G` `J` `L` | Build, Crew, Items, Trade, Camp overview, Map, Progress, Journal, camp log (a Journal tab) |
| | `O`, `Enter`, `Q` / Alt+click | Players panel, chat, ping a spot (multiplayer) |
| | `F` / `H` | The wall / horde intel |
| | `Space`, `1` `2` `3`, `4` | Pause, 1×, 2×, 4× speed, skip ahead |
| | `R`, `Shift`, `Enter`, `Esc` | While placing: rotate, place several, confirm, cancel |
| | `Ctrl`+`K` or `/` | Command palette: find and jump to anything |
| | `N` / `Shift`+`N` | Go to the next / previous problem in the camp brief |
| | Shift-click or Ctrl-click stations | Select several for mass actions |
| | Click a resource in the top bar | Its flow chart |
| | Click a station's output, then a station | Lay a belt between them |
| Supply run | Left-click, drag a box, `1`–`4`, `Tab` | Select survivors (double-tap a number to jump to them) |
| | Right-click | Move, attack a zombie, or do the default action on a container |
| | Left-click a container | Menu: search it (quiet) or break it down for materials (loud) |
| | `Z` `X` `C` `V` | Molotov, pipe bomb, noise maker, first aid kit |
| | `Enter`, `Space`, `F` | Extract at the van, pause, focus the selection |
| | `Q` / Alt+click | Ping a spot for the friends on the run |
| | Left-click a wardrobe, fridge, shelf or desk | "Barricade the door" pushes it across the nearest doorway |
| City map | `M`, `Esc` | Back to camp |
| First person | `` ` `` or `F5`, the eye button | Walk in one survivor's boots, and back to the view from above |
| | Mouse, WASD, `Shift` | Look (click the view to capture the mouse, `Esc` frees it), walk, run |
| | Left click, right click | Shoot or swing (hold for automatic fire), aim down the sights |
| | `E` | Use what you look at: search, open, climb a watchtower, a station's panel |
| | `1`–`4`, `Tab`, `G`, `F` (runs) | Become another survivor, squad follows you, flashlight |

## First person

Any time in camp or on a run, `` ` `` (the key under `Esc`) or `F5` puts you
behind one survivor's eyes in the same world: the same walls, zombies and
loot, the same rules underneath. Their own arms hold the weapon they carry,
in their skin and sleeves and work gloves.

- **Fighting** is aimed by you. Shots go where the crosshair is, spreading
  when you move or run and tightening when you aim down the sights (the
  hunting rifle's scope zooms). Headshots do double damage. Guns carry
  further than the AI's careful range but lose punch with distance, and a
  shotgun fires a spread. Melee weapons swing at what is in front of you;
  out of ammo you club with the gun. Kills, experience, wear, noise and
  ammo count exactly as they do from above.
- **On a run** you see what your eyes see: no fog-of-war veil, every
  floor of a tall building in place, ceilings and roofs on, interior walls
  up to the ceiling, darker indoors (the sky comes in through the windows)
  with a flashlight after dark (`F` any time). `E` searches, picks or
  smashes a lock, breaks things down, helps a friend up or calls the lift;
  walk into a flight of stairs to climb it. The others keep their orders,
  `G` has them follow you, and if you go down you carry on as someone
  still standing. Past the lot the real neighbourhood carries on, then the
  city's skyline in the haze.
- **In camp** `E` on a station opens its panel, on a watchtower climbs it
  (the wall is solid: fight a horde from the towers, a breach or the
  gate), on the gate opens it, on a person talks to them. Whoever you walk
  as leaves their job while you have them, and fights the horde as you.

It is drawn to the same standard as the view from above: a real sky (sun,
clouds, moon and stars, meeting the fog at the horizon), shadows placed
ahead of you, dense grass round your feet in camp, and the arms and weapon
drawn in their own pass over the world, lit by its sun and sky. Settings
has mouse sensitivity, field of view, invert mouse and head bob. Co-op
guests on a run watch from above (the leader's game runs the street).

## The long game

### Milestones and tiers
The **Progress** panel (`G`) holds a board of eight tiers with three
milestones each. A milestone is a delivery from storage. Paying it unlocks
stations, belt tiers, walls, land, recipes or camp-wide bonuses. From tier 3
on, a tier also needs the Signal to have reached a given phase, so the mast
and the factory grow together.

| Tier | Name | Milestones | Needs |
| --- | --- | --- | --- |
| 1 | Foothold | Smelter (Forge, Charcoal Kiln), Steam Power (Steam Engine), Conveyors (Belt Mk1), Palisade (log wall, two expansions) | |
| 2 | Workshop | Chemistry (Chemistry Lab, Ammo Press), Fabrication (Fabricator, Weapons, Tailor), The Signal (the mast) | |
| 3 | Power | Diesel Power (Generator, Floodlight, Turret), Electronics (Electronics Bench, Radio Tower), Sheet Metal (wall, Solar Array) | Signal phase 1 |
| 4 | Industry | Rubber Rollers (Belt Mk2, two expansions), Machining (Machine Shop), Research (Research Desk) | Signal phase 1 |
| 5 | Automation | Automation (automated stations +50%), Motor Belts (Belt Mk3), Wind Power (Wind Turbine), Outer Ring (four expansions) | Signal phase 2 |
| 6 | Fortress | Fortification (wall), Power Cells (cells, Battery Bank), Transmitter Coils | Signal phase 2 |
| 7 | Overdrive | Overclocking (power cores), Amplifiers, Field Rations (camp eats 15% less) | Signal phase 3 |
| 8 | Exodus | Convoys (outposts), Arsenal (better crafting quality), Beacon (more and better recruits) | Signal phase 3 |

The old goals live on as **starter tasks** under Progress, with a reward each.

### The Signal
The Signal Mast is a 20-metre lattice tower raised in five phases: clear it,
power it, raise the dish array, tune it, and call the coast. Each phase asks
for hundreds, then thousands, of components: plates, bolts, beams, steel,
wiring, rubber, circuits, motors, coils, power cells and amplifiers. Phase 3
also needs someone who can phase the dish array (Dana, if you find her, or
Broadcast Engineering research), and the last phase needs the Harbor Light
codebook. Deliver by hand from the Progress
panel or belt parts straight into the mast. The tower visibly grows with each
phase. When the last phase is done, the biggest horde of the game comes for
the camp. Survive it and the convoy comes.

### Pacing
- **Threat** sets horde size and toughness. It comes from what the camp has
  achieved: tier, Signal phase, population and expansions, plus a little for
  days survived (capped at day 100).
- **Blood Moons:** from day 21, every seventh night brings a horde half again
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
Every building that makes or uses goods has **pods**: teal intakes down its
left side, orange outtakes down its right (its front stays clear for the
workers), and a Storage Depot has blue two-way hatches. An arrow painted on
the ground in front of each pod shows which way goods go. One belt plugs into
one pod. Crafting benches take belts too: orders draw their materials from the
intake first, and resource recipes (plates, bolts, parts, wiring, beams) leave
by belt.

**Laying a belt:** click a pod (or "Belt in" / "Belt out" on a station, or the
Conveyor belt card under Build → Belts). Everything it could connect to lights
up with a ring on each free pod; click the building or pod where it goes. The
route and cost preview on hover. Click the ground on the way to pin bends
(right-click or Backspace removes the last); Shift-click the end to lay
another from the same building. Chips on the bar choose what the belt carries
when a building deals in several goods. Belts run on posts above head height,
route around buildings with a turn-aware search, keep off other pods, and run
a layer higher where they cross. Putting a building down across a belt
re-routes the belt round it.

**Click any belt** for its panel: what it carries, the rate now and once it
settles, what holds it back, upgrade, take down, and **Splitter here** /
**Merger here** at the spot you clicked.

| Belt | Carries | Power | Unlocked by |
| --- | --- | --- | --- |
| Mk1: salvaged rubber on scrap rails | 96 a day (one every 5 s) | 0.01 a metre | Conveyors (tier 1) |
| Mk2: proper rollers, cured belt | 192 a day (one every 2.5 s) | 0.02 a metre | Rubber Rollers (tier 4) |
| Mk3: motor-driven, steel-framed | 400 a day (one every 1.2 s) | 0.03 a metre | Motor Belts (tier 5) |

- **Splitter** (Conveyors): one belt in, up to three out. Each item goes to
  the next belt in turn, so two belts get exactly half each and three a third;
  when one is full its share goes to the others.
- **Merger** (Conveyors): up to three belts of the same goods in, one out, at
  most that belt's rate.
- **Priority Splitter** (Conveyors): one belt in, up to three out, but one of
  them goes first: it gets everything it can take, and only what backs up on
  it spills over to the others, shared between them in turn. Feed the forge
  before storage. A gold "1ST" pennant flies over the first belt; "Goes first"
  on its panel moves it. Draws 0.25 power.
- **Buffer Hopper** (Conveyors): a tank you drop on a belt line, one belt in
  and one out. It fills when more arrives than leaves and empties when the line
  runs dry, so the machine after it keeps going through bursts and gaps. It
  holds 30 loads (80 at level 2), and a gauge on the tank and in its panel
  shows how full it is. Draws 0.15 power.
- **Splitter here**, **Priority here**, **Merger here** and **Hopper here** on
  a belt's panel drop one into the line where you clicked.
- **Belts run on power.** A belt draws its tier's rate per metre, at least 0.1
  (a 20 m Mk1 belt draws 0.2, a twentieth of a level-1 Steam Engine); a
  splitter or merger draws 0.2. Power efficiency research cuts it by a
  quarter. Belts get power right after turrets and floodlights, before
  machines. Without power a belt stops where it is: its lamps go dark, nothing
  moves, and the splitter or merger on it passes nothing. The belt panel shows
  its draw, the Power panel a "Belts" line and how many are stopped, and the
  camp brief flags stopped belts.
- A station runs 15% faster for each side that is fully belted (inputs fed,
  outputs carried away).
- A station whose output is belted to a consumer works **on demand**: it makes
  what the next station needs instead of whatever it has the materials for.
- Belts upgrade in place. Moving a station reroutes its belts. Logistics
  research makes every belt carry 25% more.

**The maths.** Everything is per day.
- A belt carries `speed ÷ gap × stack × seconds a day`: Mk1 is 0.4 m/s with
  items 2 m apart, 0.2 items a second, 96 a day. A camp day is 8 real
  minutes at normal speed, so that is 12 a minute, one every 5 seconds.
- A building runs at most `Bmax = seconds a day × rate × belt bonus × season
  ÷ batch time` batches a day, where rate is its workers' speed plus
  automation. Each batch takes `in[k]` and makes `out[k]`.
- It actually runs `B = min(Bmax, belted input k ÷ in[k], room on belted
  output k ÷ out[k])`. Inputs without a belt come from storage and never
  hold it back.
- What it makes is shared over its belts for that good: equal shares, and a
  belt that can take less (full, or its far end needs less) passes the rest
  on. A splitter shares what it gets the same way; a merger sums its inputs up
  to its belt's cap.
- These limits feed back both ways (a slow forge backs up the scrap belt that
  feeds it, which slows the scrap yard), so the game settles the whole network
  by repeating both passes until nothing changes, starting from every belt
  full. Each belt then reports the rate it settles at and what holds it back:
  the belt itself, the far end's appetite, the near end running dry, or the
  far end being short of another input.
- In a test chain (an automated scrap yard feeding two forges through a
  splitter on a Mk1 belt) the predicted 96 / 48 / 48 a day matched the
  simulated 93 / 45.3 / 44.8.

Machines spin up quickly and run down slowly, follow pause and game speed, and
a machine paced by its belt says so ("Paced by its belt · 64%") instead of
flashing a warning; real stoppages show once they have lasted a few seconds.
Goods slide out of outtake hatches and into intakes and turn smoothly at
corners. Whatever has no belt still goes by hand, and the haulers you see
carry exactly those goods.

### The materials ladder
- **Raw:** scrap, wood, water, food, fuel.
- **Smelted:** the Forge melts scrap into **metal bars**. The Charcoal Kiln
  bakes wood into **coal**.
- **Shaped:** bars become **plates** and **bolts**, at the Workbench by hand or
  in the Fabricator by machine. A level 2 Forge folds bars and coal into
  **steel**; steel and bolts make structural **beams** (Workbench level 2 or
  the Machine Shop).
- **Components:** wiring and electronics (Fabricator), rubber and power cells
  (Chemistry Lab), circuits, motors, transmitter coils and amplifiers (Machine
  Shop).
- Level 1 of every station is built from simple stuff. Upgrades, the wall, the
  mast and the top tiers need plates, bolts and beams.
- Anything a machine makes can be made by hand at a bench, slower. Machines
  need power; unpowered, their crew works them by hand at half speed.

### The Recycler
- Unlocked with Fabrication. Gear you mark **Recycle** in Items (or standing
  orders: strip broken gear, strip crude spares) is broken down first, for 45%
  of what it took to make (60% at level 2), less for worn gear, plus half of
  any mods. Gear that can't be crafted pays out by its value.
- Between jobs it works through junk off a belt or from storage: tyres into
  rubber, dead car batteries into chemicals, and scrap sorted into parts,
  wiring and bars. It always keeps four tyres and a battery back for the van.
- Level 2 can run by itself with an Automation Module.

### Power
- **Steam Engine** (tier 1): burns wood, or coal for three times as long, only
  as hard as the load needs. 4 / 7 / 11 power by level.
- **Generator** (tier 3): diesel, 9 / 15 / 24 power.
- **Solar Array** (daylight) and **Wind Turbine** (better in storms and snow,
  useless in still fog) cost nothing to run.
- **Battery Bank** stores what solar and wind make beyond the load and gives
  it back at night.
- **Off the grid**, cheap power that costs nothing to run:
  - **Pedal Generator** (from the start): an exercise bike on an alternator,
    about 1.4 power per rider, day or night, while someone pedals. Level 2
    adds a second bike.
  - **Solar Panel** (Steam Power, tier 1): two salvaged panels, 1.2 power at
    noon; level 2 puts them on a mast that turns with the sun (2.2 at noon
    and much more in the mornings and evenings).
  - **Biogas Digester** (Chemistry, tier 2): dung from the coop and goat pen
    and a staffed Cookhouse's scraps rot down into gas: 3 / 5 power day and
    night when it gets 8 / 12 loads a day (a hen leaves 0.3, a goat 1.2, a
    Cookhouse 2). It can be fed food when dung runs short, and while it is
    fed the farm plots grow 10% more. Its gas holder visibly rises and falls.
  - **Solar Lamp** (Steam Power, tier 1): a lamp post with its own panel and
    battery that lights about 9 m round it all night with no grid power.
    Defenders in its light see in the dark during a horde, and pens close by
    are harder for the infected to get at. After a dull day it can go dark
    before dawn.
- Sun, wind, pedals and gas are used first; engines burn only for the rest.
  When power is short, defence gets it first, then belts, splitters and
  mergers, then staffed machines, then automation. The Power panel shows every
  source and every user.

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

### Tools for a big camp
- **Command palette** (`Ctrl`+`K` or `/`): type a few letters to find any
  station, survivor, resource, item, place in the city, panel or thing to
  build, and jump straight to it (the camera flies there too). Arrow keys move,
  `Enter` opens; with nothing typed it lists what you opened last.
- **Flow charts:** click a resource in the top bar (or a row in the Camp
  overview) for a diagram of where it comes from and where it goes, every band
  as wide as its rate per day, with the stock in the middle, how long until it
  runs out or fills, the last four days, and the belts carrying it. Click a
  station to open it.
- **Watch list:** the pin on a station, survivor or resource panel puts it on
  a strip down the left of the screen with its numbers live: stock and rate,
  how hard a station works, how someone is doing. Up to ten, kept per camp.
- **Custom alerts:** "tell me when metal passes 200", "when anyone is hurt",
  "when the forge stalls", plus storage full, infection, idle hands, a horde
  under N hours away, power short and buildings finished. Each fires once when
  it comes true (a toast you can click through, a chime, a desktop notification
  in the background) and can pause the game. Open them from the palette.
- **Go to problem:** `N` flies the camera to the next thing in the camp brief
  that needs you and opens it; `Shift`+`N` goes back. The brief has a "Go to
  each" button too.
- **Mass actions:** Shift-click (or Ctrl-click) stations to select several, or
  "All N" on a station's panel to select every one of its kind. Then upgrade
  them all, pause or resume them, switch their automation, fill their empty
  jobs with the best free hands, or set a shared production target.

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
- **Sound you can place:** groans, glass and screams come from where the
  infected are. They pan left or right with the camera, fade with distance and
  are muffled through walls, measured from your nearest survivor.
- A **minimap** shows the explored layout, the squad, seen zombies and sound
  pings.
- **Picked clean:** in the Scavenger mode a searched place stays empty for
  four hours of real time, then restocks. The city map counts the time down.
- **Tall buildings:** apartment blocks, offices, police stations, schools and
  hospitals have two to four floors, and the tallest have a roof (hospitals
  with a helipad and sometimes the helicopter). A stairwell in the entrance
  hall links them; right-click the stairs to climb, or anywhere on another
  floor. The view follows the selected survivor; `[` `]` or the Floors bar look
  at any floor. Noise carries up and down the stairwell, so a shot upstairs
  brings the floor below after you. A backup generator lights the building and
  runs the lift, which the infected cannot use, but starting it is very loud.
- **Barricades:** left-click a wardrobe, fridge, bookshelf, locker, desk,
  shelf or crate and pick **Barricade the door**: a survivor pushes it across
  the nearest doorway of its room. The menu says whether that shuts the room or
  whether there is another way in to block too. The dead that want in hammer at
  it (a heavy wardrobe holds a few minutes against one or two, a health bar
  shows how long it has left), and others come to the noise and join in. While
  it holds, anyone resting inside a shut room patches up to 85% health.
  **Take it down** slides it back; a broken one topples and opens the door.
- **Tracks in the snow:** in winter everyone outdoors leaves prints. The
  infected's prints show where they went; an infected that finds the squad's
  fresh trail follows it back to the van.

## The city

- **Two modes**, chosen when a camp starts (and when a multiplayer camp
  starts). **Scavenger:** places restock four real hours after a search.
  **Last Pickings:** everything can be looted once; what was taken stays
  taken, and the map shows how much of each place is left. Both have the whole
  campaign.
- **Liberation:** every place remembers how many infected were left alive.
  Get out with nothing alive inside and it is clear; an outpost keeps it
  clear, otherwise they can drift back. Clearing districts brings out people
  with supplies; 10/25/50/75% of the city cleared gives morale, faster
  newcomers, cheaper trips and better outposts; 100% is a second ending.
- **When a camp falls**, a closing sequence plays over the ruins: how it
  ended, how long it held, its record (days, kills, places and things
  searched, built, hordes, people, kilometres, notes) and the names of
  everyone it lost. A horde that leaves nobody standing can now wipe a camp.
- **Infected counts change every day.** Each place has its usual number (by
  size and level), drifting a few either way each morning; a place you left
  half-cleared slowly fills up again. Tall buildings hold more on every floor.
- **The horde** is a great mass of the dead (55 to 75 at first, growing a
  little most days, up to 140) that walks the city, about 36 m an hour, lingering three
  to twelve hours wherever it stops. It mostly roams the bigger, higher-level
  places downtown and sometimes wanders into the quiet streets. Anywhere within
  320 m of it holds far more infected than usual, up to 60% of the horde.
- **Recon scouts:** only a scout can find it. Send one quiet survivor (scouts,
  hunters, the Quiet trait, a ghillie poncho) from a place's card to watch it
  for four to six hours, there and back on foot: they bring back the count and
  kinds inside, the floor plan (the squad goes in with the rooms mapped), and
  the horde if it is within a kilometre. Or send them to hunt the horde itself
  (8 to 12 hours, dangerous). The map then marks where it was last seen, how
  big it was and which way it was heading, and that mark ages: it keeps
  moving. Scouts can come back hurt or bitten, and on the worst places they
  sometimes don't come back.
- **Special infected:** stalkers creep up while nobody is looking, screamers
  call every infected nearby, and bloaters burst into an infectious cloud.
  Hidden traps wait in the dark; scouts spot them from much further away.

Runs still work as before: up to four survivors with their own gear, noise
draws the dead, packs fill up and loot is only safe once it is stashed, and a
horde timer runs on every run. A downed survivor bleeds out in 40 seconds
unless a teammate helps them up.

### Travel and the motor pool
- Every survivor carries food and water for the round trip: per person,
  `(0.3 + 2·km + 8·km²)` food and 1.2 times that in water, scaled by how they
  travel. Next door (0.25 km) is about 1 food on foot; four streets over
  (0.7 km) about 6; across town (1.2 km) about 14; the highway (2.5 km) over 55.
- **On foot** costs full provisions, takes three times as long and stashes
  nothing beyond what the squad carries. **Bicycles** (Workbench) cost 60% and
  move at 60% speed. **Cars** cost 30% plus fuel and have a 90-unit boot. **The
  van** and the **armoured truck** carry everything.
- The camp's van starts dead: it needs a car battery, four tyres, parts, bolts
  and fuel. Strip wrecked cars on runs for tyres and batteries.
- Now and then a car on a run still works: find its keys in the building, or
  let a mechanic, ex-con or engineer hotwire it (slow and noisy). It drives
  home and joins the motor pool. Vehicles wear a little every trip.

### The story
- Five threads run through the city: the dead van, a radio engineer
  broadcasting from somewhere near the mast, the biotech company that started
  it, the codes Coastal Command will answer to, and a convoy truck that never
  arrived. The **Journal** (`J`) keeps what you know and where it might lead.
- Notes turn up in desks, cabinets, lockers and shelves. Clues narrow a thread
  down to a handful of places; leads show violet on the city map.
- People in camp ask you to find relatives. Those trails go cold in a week.

## Survival

- **Infection:** a bite can only infect someone below 30% health, and then it
  has a 6% chance (a bloater's gas 4%, at any health). It
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

- **The camp brief**, top right, gathers everything that needs you (food or
  water running out, a horde getting close, a worn wall,
  unpowered stations, infection, morale, rewards to claim, someone at the
  gate), each a click from its fix, and names what is next: a starter task,
  the milestone you are closest to, a place worth clearing. A new camp opens
  on a four-step guided first day (water, a first run, a weapon, the wall).
- **Skip ahead** (the last speed button, or `4`) runs the camp at 12× until a
  build is done or to dawn (dusk by day), stopping for the horde, a visitor,
  a call from the city or a new problem. Solo and host only.
- **Fill with the best free** on a station puts the free survivors best at
  that job to work in one click. **Same again** on the map sets up the last
  run (place, people, vehicle). Put a station in the wrong spot? **Undo** on
  the toast (or `Ctrl`+`Z`) within 10 seconds takes it down at full refund.
- Settings has an **interface size** (90 to 130%), **background alerts**
  (a desktop notification for the horde or a visitor while the tab is in the
  background) and the first-day guide switch.
- You start in an old lumber yard with four survivors, a campfire, a tent
  bunkhouse, a farm plot, a rain collector, a storage depot and a workbench.
- **Storage in sizes:** the Storage Depot (120 / 260 / 450), a **Crate
  Stack** (2×2, +35, from the start), a **Storage Shed** (3×3, +90,
  Fabrication) and a **Warehouse** (7×5, +380, Machining). They all add to one
  shared camp store, and any of them can take belts in and send them out.
- **Stockpiles you can see:** every store's floor and racks fill with crates,
  logs, sacks, drums, bars and boxes as the camp's stock of each kind rises,
  in five steps from bare pallets to piled high. More stores show more kinds.
- **Livestock:** a **Chicken Coop** (from the start) keeps 6 hens (10 at level
  2) that lay eggs; a **Goat Pen** (Palisade) keeps 3 to 5 goats for milk and
  wool, and the Tailor spins 2 wool into 3 cloth. Eggs and milk are eaten
  before the stores and lift morale. With a keeper and the camp fed, chicks and
  kids come along every few days; buy more from a passing trader. At night
  their noise draws the infected (every pen adds 8% to each horde): an
  unfenced pen has a 30% chance a night of losing an animal, a picket fence
  cuts that to about a tenth and a stout one to 3%, and a fence that is hit
  needs mending.
- **Diaries:** each survivor writes short entries in their own voice about
  what happens to them: joining, their job, the weather, runs, hordes, wounds,
  friends lost, a new name. They're on their sheet; the Memorial keeps the last
  thing each of the dead wrote.
- Every survivor eats about 2 food and drinks 2.4 water a day. If food or water
  runs out, everyone weakens and works at 60%.
- **Upkeep:** each day the camp wears through a little: 0.15 cloth and 0.02
  meds per survivor, 0.15 scrap per station level, bolts for upgraded stations
  and parts for machines. Short on any of it and the camp falls into
  disrepair: stations work at 85% and morale drops until it is paid.
- **Gear swapping:** in camp, click any equipment slot to take from storage or
  from another survivor (they swap). Gear stays put while someone is on a run
  or at an outpost.
- Survivors without a job build whatever is under construction. When nothing
  is going up, they forage the yard for a little wood and scrap.
- Morale comes from beds, hot meals, the fire, the wall and recent events. High
  morale makes everyone work up to 20% faster. Below 18, people start leaving
  in the night.
- **Expansions:** each side of the fence can be pushed out twice. Each one costs
  materials and cash, takes a crew time to clear, and pays back salvage.
- **Earned names:** survivors pick up nicknames and a short bio from what
  they do ("Two-Shot Mara", "Greenthumb", "the Angel"). Bigger deeds replace
  smaller ones. You can rename them in the Their story section of their sheet.
- **Survivors:** 25 pre-outbreak jobs (the Scout is new), eight skills,
  traits with strengths and flaws, a weapon, armor and gear slot each, item
  quality and condition, mods, and permanent death recorded in the Memorial.
- **Crafting benches** work through an order queue (once, N times, or keep
  stocked) and keep the crew's gear repaired.
- **Automation:** give a station of the right level an Automation Module and
  power, and it runs with nobody assigned.
- The **black market** buys and sells. Its stock changes at dawn.
- **Distress calls and supply drops** appear on the city map for a while.

## Multiplayer

- The **host** runs the camp: the simulation, the save (separate from their
  single-player camp) and the final say on every change. **Guests** get the
  whole camp when they join, then compact diffs a few times a second.
- Whatever a guest does changes their screen at once. The difference goes to
  the host, which checks it (do they lead that survivor? is there enough in
  storage?), applies it and confirms it, or turns it down and the guest's
  screen snaps back.
- **Who leads whom:** the host hands survivors to players in the Players panel
  (`O`), or shares the unled ones out evenly. You give orders only to your own
  survivors and to anyone nobody leads. Whoever rescues or recruits someone
  leads them.
- **Runs alone** play out on each player's own machine, several at once. What
  they bring home lands in the shared stores. If someone drops out mid-run,
  their squad walks home with nothing.
- **Runs together:** plan a run on the city map and press *Invite friends*.
  Everyone in camp gets a card and joins with up to four of their own
  survivors (six on a run in all). The player who planned it leads: their game
  runs the street and pays the trip. Friends build the same street from the
  run's seed and follow a live stream of it (every survivor and zombie,
  containers opened or broken, traps, shots, fire, finds), giving orders to
  their own survivors, which travel to the leader's game. Sight is shared. If
  the leader drops out, everyone else's survivors make their own way home.
- One camp clock for everyone, set by the host. It does not slow down while
  someone is on a run.
- **Raids** are fought in the host's camp and streamed to everyone. Select a
  defender you lead and right-click to move or pick a target; the host's
  game carries the order out.
- **Lockers:** each player has one. *Keep* moves an item or resources from
  camp storage into it; only that player's survivors can wear locker items,
  and nobody else can take or sell them. Give items to a friend, or offer a
  trade (Players panel, *Trade*): they accept or turn it down, and nothing
  moves until they accept. A forgotten player's locker goes back to camp.
- Chat with `Enter`. Coloured rings show where each friend is looking; `Q` or
  Alt+click pings a spot for everyone. Coming back after a while, you get a
  summary of what happened since you were last in camp.
- **Connections:** inside Claude, the artifact's live room (friends open the
  same artifact; camps being hosted show up in the list). On the Holdout
  website, a WebSocket to its server. The standalone file uses WebRTC through
  PeerJS. `?net=tabs` connects tabs of one browser for testing, and
  `?as=Name` plays as someone else in the same browser.

### Always-on camps

On the website, *Always-on camp* under Multiplayer makes a camp that lives on
the server. The server runs the real game code for it in a worker thread, and
whoever made it is its admin (hands out survivors, sets the pace, can kick).

- It wakes when someone joins and goes back to disk two minutes after the last
  player leaves.
- While nobody is on, it catches up the way single player does: the crew works
  at a slow pace and the clock waits, for up to three days of real time. No
  horde comes while the camp is empty.
- When a horde comes, the server hands the defence to a player in camp (the
  admin first), whose game fights it live and streams it to everyone. If they
  drop out mid-fight, or nobody is in camp, the fight is worked out by the
  numbers.

### The server and fly.io

`server/server.mjs` is a small Node server: it serves the game page (gzip and
brotli), relays hosted camps between browsers over WebSockets, runs always-on
camps in worker threads and saves them to `DATA_DIR/camps/`.

```bash
npm run server:build   # builds server/public/index.html and server/dist/camp.mjs
npm run server         # http://localhost:8080
```

**The easy way:** the repository's GitHub Action
(`.github/workflows/holdout-fly.yml`) deploys it. Add a fly.io organization
token as the repository secret `FLY_API_TOKEN` (Settings → Secrets and
variables → Actions) and re-run the workflow, or push a change under
`holdout/`. The first run creates the app named in `fly.toml` and its volume;
the job summary prints the address, `https://<app>.fly.dev`. If fly says the
app name is taken, change `app` in `fly.toml`.

**By hand** (from this `holdout` folder, with `flyctl` installed and logged
in):

```bash
fly apps create holdout-jrpetty                    # or the name you put in fly.toml
fly volumes create holdout_data --size 1 -r lhr    # same region as primary_region
fly deploy --ha=false                              # one machine: the camps live on its volume
```

The machine sleeps when nobody is connected and wakes on the next visit (camps
are saved on shutdown and catch up when loaded). `MAX_CAMPS` (200),
`MAX_LOADED` (12) and `UNLOAD_AFTER_MS` (120000) tune the limits; anyone may
start up to six camps an hour. `GET /healthz` answers for fly's checks and
`GET /api/camps` lists the public camps.

## Development

```bash
cd holdout
npm install
npm run build        # writes ../holdout.html
```

The code is plain ES modules in `src/`. `build.mjs` bundles them with esbuild
into one HTML file, and `node build.mjs --out FILE --fragment` writes just the
page body. `--debug` keeps function names readable for profiling, and
`?shaders` in the address turns three.js's shader error checks back on (they
are off because each check waits on the graphics card).

Things that keep frames smooth: shaders for every station, the placement
ghosts and a survivor portrait are compiled in the background after loading;
a run or the city map compiles and uploads behind its loading card; portraits
are drawn one at a time between frames; fire light comes from a fixed pool
(adding a light recompiles every material); and *Auto resolution* in Settings
lowers the render resolution a little when the frame rate drops.

Things that keep the graphics card's load down: trees, rocks and grass are
kept in grid cells and only the cells the camera or the sun's shadow box can
reach go into the instance buffers (`render/instcull.js`); trees past 44
metres swap to a cheaper model; foliage cards draw a depth-only pass first so
their shading runs once per pixel; and *Frame rate* in Settings (Smart, the
default) draws every 2nd or 3rd refresh on a 120 Hz-plus screen, keeping 60
or more frames a second evenly paced (`render/pacer.js`). `node dev-build.mjs src/dev/<viewer>.js out.html` builds the model,
character, station and lighting viewers used while modelling.

```
src/
  main.js            boot, title screen, scene switching, offline catch-up,
                     main loop
  net/
    transport.js     the carriers: claude.ai room, PeerJS, BroadcastChannel,
                     WebSocket server; framing, compression and chunking
    delta.js         structural diffs with keyed records and rounding
    mp.js            sessions: snapshots, diffs, guest patches, permissions,
                     event forwarding, chat, raid captains, relays
    coop.js          co-op runs: forming a party, setting out, coming home
  game/
    data.js          every definition: resources, jobs, skills, perks, items,
                     stations, recipes, belts, milestones, the Signal,
                     research, seasons, infection, outposts, locations, zombies
    state.js         game state, survivors, milestones, research, outposts,
                     vehicles, saving, backups, export and import
    story.js         story threads, clues, leads, notes, personal requests
    storydata.js     the story's text: threads, notes, people
    economy.js       the camp simulation: production, crafting, power, morale,
                     seasons, infection, threat, hordes, outposts, the market
    belts.js         belt routing, rates, buffers and the belt simulation
  scenes/
    base*.js         the camp: stations, people, belts, fence, horde fights
    raidnet.js       raids streamed from the host to guests
    citymap.js       the 3D city map, squad planner and convoys
    mission*.js      supply runs: interiors, looting, sight, traps, extraction,
                     and missioncoop.js, the co-op run stream
  world/
    city.js          seeded city layout: streets, districts, lots, locations
    levelgen.js      supply-run levels: floor plans, rooms, furniture, loot
    vision.js        line of sight, room reveal, wall sense, memory
    agents.js        survivor and zombie AI and combat
  models/            procedural models: skinned characters, stations, the
                     mast, furniture, weapons, vehicles, city
  render/            pipeline (SSAO, bloom, SMAA, tilt-shift), fog of war,
                     sky, terrain, snow and seasons, materials, effects,
                     scenery culling, frame pacing, first person (the
                     controller, the arms-and-weapon overlay, the sky)
  ui/                HUD, panels, progress board, field manual, journal,
                     motor pool, lobby, Players panel and pings
server/
  server.mjs         the website: page, WebSocket relay, camp list, limits
  camp.mjs           one always-on camp in a worker thread
  build.mjs          bundles the page and the camp worker
Dockerfile, fly.toml the fly.io deployment
  core/              audio, A* grid, utilities
```

[`DESIGN.md`](DESIGN.md) has the design analysis behind v3 and notes on where
the shipped game differs from the plan.
