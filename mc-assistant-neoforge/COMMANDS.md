# MC Assistant — Complete Command & Feature Reference

Every phrase works **three ways**: bare chat (`gather 32 logs`), addressed
(`assistant, ...` or by name: `bob, ...`), or classic (`!gather logs 32`) —
and **all of it works by voice**: hold `V` (rebindable), speak, release.
Orders chain with **"and" / "then"** and run as a sequential job queue.

**Voice is always directed at the assistant.** Anything you speak is sent
to chat as an explicit command (`gather twenty logs` → `!gather twenty
logs`), so the AI always responds. **Spoken numbers are understood** —
"twenty" = 20, "one hundred twenty eight" = 128, "a stack" = 64, "two
stacks" = 128, "ten by ten" = 10×10.

## 1. Crew & identity

| Say | Does |
|---|---|
| `spawn` / `summon` | Creates your first assistant |
| `spawn a miner named bob` | Named + role spawn (roles: miner, lumberjack, farmer, builder; up to 10 per player) |
| `bob, <anything>` | Routes any order to that assistant by name |
| `everyone gather 128 iron` / `team, get 64 logs` | **Crew order** — splits the job across the whole crew, each hauling its share to the shared depot (divided labor) |
| **Job Board** block (place + right-click) | Sets the crew's role preset and marks the town center. Right-click cycles **Auto → Mining → Balanced → Food → Build**. Autonomous crew within 64 blocks self-assign roles around it — Auto balances to whatever the depot chest beside the board is short on (food/wood/stone); a fixed preset biases the whole crew. Craft: 8 planks around a book. |
| `your name is X` / `call yourself X` | Rename |
| `be a miner` / `you're a farmer` | Assign role |
| `dismiss` / `go away` | Sends it away — drops all gear first |

## 2. Modes & movement

| Say | Does |
|---|---|
| `follow me` | Default mode |
| `stay` / `stand` (also `wait`/`hold` when addressed) | Holds position |
| `guard me` / `protect` / `defend` | Fights hostiles near you or it |
| `come here` / `to me` | Comes to you, keeps following |
| `stop` / `halt` / `cancel` / `never mind` | Clears the whole queue |
| `go home` | Walks to its home point and holds |
| `set home here` / `this is home` | Sets home at its spot |
| `remember this spot as the mine` / `call this place X` | Saves a named waypoint |
| **Place Marker** item (rename in an anvil → right-click a spot) | Saves that spot (or where you stand, if you right-click air) as that named waypoint — the hand-held way to name a place. Craft: paper over a stick |
| `go to the mine` | Travels there — a real queued job, chains in order |
| `go to 120 64 -300` / `meet me at ...` | Navigates to raw coordinates |
| `boat to 200 63 -400` | Sails a carried boat toward the coords (experimental) |
| `go to the nether and get glowstone` | Builds a portal, crosses, gathers, returns (experimental) |
| `places` / `where can you go` | Lists known waypoints |
| `patrol between home and the mine` | Endless guard laps between two places; `stop` ends it |

## 3. Gathering, mining & area work

| Say | Does |
|---|---|
| `gather 128 logs` (also stone, dirt, iron, coal) | Amounts to 1024; `2 stacks` = 128; verb carries over: `gather 64 logs and 32 stone`. Ores need the right pickaxe tier |
| `dig a mine` / `mine down to level 12` | Torch-lit staircase to depth + 24-block gallery, chases ore veins, bridges cavities, refuses to dig into lava/water |
| `clear a 10x10 area` / `flatten` | Strips the region 3 high, keeps drops, skips fluids/bedrock |
| `light up the area` | Torch grid until nothing can spawn nearby |
| `bridge forward` / `build a bridge` | Places floor across gaps/rivers/lava to solid ground |
| `pick up the items around here` | Sweeps all drops within 16 blocks |

## 4. Storage & items

| Say | Does |
|---|---|
| `deposit` / `stash it` / `put it in the chest` | Unloads to nearest chest (or a remembered one up to ~160 blocks) |
| `grab 10 iron from the chest` | Withdraws — it remembers which chest holds what |
| `give me 10 torches` / `hand me the logs` | Delivers straight into your inventory |
| `give me my stuff` / `give me everything` | Hands over the whole pack (post-death recovery) |
| `what are you carrying?` / `inventory` | Itemized pack report |
| `open` / `show me your pack` | Opens the management GUI |
| `keep the chest stocked with 64 logs` | Standing order — restocks forever; `stop keeping` cancels |
| `sort the storage` | Consolidates each item type into one home chest |
| `how much iron?` / `do we have any coal?` / `have we got diamonds?` | Totals across the pack + every remembered chest **plus a live scan of chests right next to it** (verb is optional now) |
| `enchant your gear` | Spends earned XP + lapis at an enchanting table |

## 5. Crafting, smelting & repair

| Say | Does |
|---|---|
| `make me an iron sword` / `craft a piston` / `make a hopper` | **Any vanilla recipe** — read from the game's own recipe database (tools, armor, blocks, redstone, rails, doors, dyed blocks, food…). The planner **sources every missing part itself**: gathers wood/stone/sand/gravel, **mines to depth for gold/copper/diamond/redstone/lapis**, **hunts animals for leather/feather/meat/wool**, smelts, crafts sub-parts, pulls from a chest within 24 blocks, and only asks you for what it truly can't get (spider/creeper drops, sugar cane). Places its own crafting table when a recipe needs the 3×3 grid |
| `smelt 16 iron` / `smelt logs` / `cook the beef` | Runs a real furnace with pack fuel: iron/gold/copper → ingots, logs → charcoal, cobble → stone, sand → glass, raw foods → cooked |
| `repair your pickaxe` / `fix your tools` | Combines two damaged same-type tools grindstone-style |

## 6. Building

`build a ...` — **house/home** (a real 5x5 home: floor, 4-high walls, windows,
door, roof, and a workbench + furnace + chest inside), **room** (walled + floored
+ lit), **pen/barn/enclosure** (7x7 fence ring with a gate, for animals),
**wall**, **platform**, **shelter** (hut), **smeltery** (3 furnaces facing the
door, 2 chests, crafting table, torch), **storage** (4 chests), **workshop**
(table + furnace + chest), **watchtower** (3x3 tower, ladder shaft, walled deck).
Every part is a real item from its pack; missing parts are itemized with the
craft order to say — so "build me a house" gathers/crafts what it lacks first.

`fortify` (also `fortify the base` / `wall off the base` / `build a wall around
the base`) — rings the whole base with a **defensive perimeter wall**, 3 blocks
high, centered on **home** (if set, else where it's standing), with a one-wide
chokepoint doorway and a torch on every corner so nothing spawns along it.

## 7. Farming & animals

| Say | Does |
|---|---|
| `tend the farm` / `harvest the crops` | Harvests mature wheat/carrots/potatoes/beetroot and replants — and if there's no farm yet, **bootstraps one**: breaks grass for seeds, tills dirt into farmland, plants a plot |
| `hunt 3 cows` (pigs/chickens/sheep/rabbits) | Adults only, never named pets; sweeps all drops |
| `breed the cows` | Feeds pairs their real breeding food (wheat/carrots/seeds) |
| `bring 2 cows home` / `lead the sheep back` | Leashes with real leads (craftable) → "pen" waypoint or home, recovers leads |
| `shear the sheep` | Real shears (craftable), sweeps wool |
| `catch 5 fish` | Real rod (craftable), real bite times, vanilla-style loot |

## 8. Awareness & autonomy

| Say | Does |
|---|---|
| `what do you see?` / `look around` | Nearby hostiles, animals, ground loot |
| `find the nearest village` (mineshaft/shipwreck/stronghold/portal) | Real structure locator → distance + bearing + saved waypoint (`go to village`) |
| `survive` / `fend for yourself` / `work on your own` | Survival brain on. **Stay alive:** food, a working pickaxe, tool repair, stash-when-full, **walls itself in at night when monsters are near** (out at dawn), and **won't fight a losing battle** — flees toward light when hurt or outnumbered, holds its ground once armored. **Then thrive:** climbs the tech tree by highest-value step — stone tools + furnace + torches → mines/smelts/forges full **iron armor** (auto-worn) + sword + shield + iron pickaxe → **diamond gear** → **enchants** at a table → renewable food. Near a **Job Board** it self-assigns a town role and fills the crew's needs. Add a role for stockpiling too. |
| `take a break` / `wait for orders` | Autonomy off |
| `tend the farm every 20 minutes` (also: `deposit` / `sort the storage` / `hunt` / `pick up items` / `light up the area` every N minutes/hours) | **Recurring chore** on a timer — re-fires whenever it's idle and the interval is due. Works with or without autonomy; never interrupts a job. `stop routines` cancels. Survives relogs. |
| `head home at night` / `work nights` | Night routine on/off |
| `status` / `how are you` | HP, mode, role, auto, items, food, jobs, standing orders, routines |
| `version` / `what build` | Reports the build stamp — the quickest way to confirm the loaded jar is current |
| `run diagnostics` / `self-test` / `test yourself` | Performs each core skill in front of you — places a block & mines it back, pathfinds, round-trips a waypoint, resolves a recipe — and reports pass/fail per skill. Cleans up after itself |
| `jobs` / `what are you doing` | The queue, in order |
| `help` / `what can you do` | In-game cheat sheet |

## 9. Slash commands

`/assistant` + `spawn · follow · stay · guard · come · stop · home · sethome ·
goto <place|x y z> · mark <place> · gather <what> [n] · mine [level] · deposit ·
withdraw <item> [n] · give <item> [n] · make <what> · craft <what> ·
smelt <what> [n] · build <structure> · farm · hunt [animal] [n] · shear ·
breed [animal] · herd <animal> [n] · fish [n] · cleanup · sort · enchant [what] ·
stock <item> · needs ·
nether <target> [n] · boat <x y z> · patrol <a> <b> · clear [size] ·
lightup [radius] · bridge · jobs · status · open · role <role> ·
auto <on|off> · night <on|off> · rename <name> · dismiss`

## 10. Automatic systems (no command needed)

- **Voice engine** — offline Vosk speech recognition; one-time ~40 MB model
  download to `config/mc_assistant/`; mic open only while V is held; audio
  never leaves the machine.
- **Grave guard** — if the owner dies within ~128 blocks, the nearest
  assistant races over and secures the entire drop, unprompted.
- **Health & hunger** — visible colored HP nametag; heals by eating real
  food from its pack (nutrition = hearts); near-zero regen when starving;
  drops everything on death; owner friendly-fire halved.
- **Threat-aware retreat** — it won't trade blows in a fight it should lose
  (badly hurt, or outnumbered with no way to heal). It breaks off, heads home
  or to you if it can, and solo in the dark **runs toward the brightest ground
  it can find** (light = fewer mobs), eats back to health, then resumes the
  paused job. Well-armored it stands and fights; naked it flees.
- **Creeper dodge** — a hissing creeper triggers an instant sprint out of
  blast radius, overriding everything else.
- **Totem & shield** — a carried totem of undying is auto-held in the
  off-hand (a real second life); a shield soaks half of frontal hits.
- **Armor & weapon auto-equip** — wears the best armor it carries; draws
  the bow for creepers/distant targets, best sword/axe otherwise.
- **Tool intelligence** — best tool per block, dig speed scales with the
  tool, real durability + break warnings, ore tool-tier rules.
- **Ranged combat** — kites creepers at 9+ blocks, consumes real arrows,
  wears the bow down.
- **Danger callouts** — "CREEPER east of you — move!" with compass
  directions; general hostile warnings near the owner.
- **Auto-deposit when full** — stashes mid-job and resumes the remaining
  amount automatically.
- **Quartermaster** — if the owner is nearby and running on empty while the
  assistant carries spare food, it walks over and hands some off, unprompted.
- **Distress call** — a crewmate taking real damage while genuinely in trouble
  (hurt or facing 2+ mobs) rallies nearby idle, able crew: they equip their best
  weapon, rush over, and pile onto the attacker. Teamwork under fire, no orders.
- **Scheduled chores** — recurring upkeep on a timer (`tend the farm every
  20 minutes`); fires when idle and due, survives relogs, `stop routines` ends it.
- **Storage memory** — remembers the contents of every chest it touches.
- **Tree replanting** — a sapling goes back on every stump it makes.
- **Worksite lighting & doors** — torches dark work spots; opens/closes
  doors while pathing; mines are torch-lit as dug.
- **Idle backoff** — autonomous work pauses ~2 minutes when an area is dry.
- **Town coordination** — with a Job Board placed, an autonomous crew
  self-assigns roles (Auto rebalances to the depot's shortfalls) and shares a
  needs board: a member short on iron posts it, an idle miner mines it for the
  group and hauls it to the depot — divided labour with no micromanaging.
- **Persistence** — name, role, home, waypoints, inventory, equipment,
  standing orders, scheduled routines, and toggles all survive relogs.
- **Safety rails** — never digs into lava/water, skips bedrock, handles
  falling gravel, spawns collision-safe (no suffocation).
- **Chat hygiene** — casual chat is never hijacked; addressed messages
  always get a reply; a bad command never breaks the listener.
- **Assistant Spawner block** — craftable (8 iron + 1 diamond); right-click
  to summon your assistant and set its home point.
- **Place Marker item** — craftable (paper over a stick). Rename it in an
  anvil to a place name ("mine", "base"), then right-click a block to save
  that spot (or right-click air to save where you stand) as that named
  waypoint. Reusable — rename and re-use to label as many places as you like.
