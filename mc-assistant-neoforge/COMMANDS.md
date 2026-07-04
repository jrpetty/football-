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
| `how much iron do we have?` | Totals across the pack + every remembered chest |
| `enchant your gear` | Spends earned XP + lapis at an enchanting table |

## 5. Crafting, smelting & repair

| Say | Does |
|---|---|
| `craft N X` / `make a X` | 28 recipes: planks, sticks, crafting table, chest, torches, furnace, ladders, bread, bow, arrows, fishing rod, leads, shears, bucket, wooden/stone/iron pickaxe/axe/shovel/hoe/sword, iron helmet/chestplate/leggings/boots. Tool recipes use a crafting table — it places its own if none is near |
| `smelt 16 iron` / `smelt logs` / `cook the beef` | Runs a real furnace with pack fuel: iron/gold/copper → ingots, logs → charcoal, cobble → stone, sand → glass, raw foods → cooked |
| `repair your pickaxe` / `fix your tools` | Combines two damaged same-type tools grindstone-style |

## 6. Building

`build a ...` — **wall**, **platform**, **shelter** (hut/house), **smeltery**
(furnace building: 3 furnaces facing the door, 2 chests, crafting table,
torch), **storage** (4 chests), **workshop** (table + furnace + chest),
**watchtower** (3x3 tower, ladder shaft, walled deck). Every part is a real
item from its pack; missing parts are itemized with the craft order to say.

## 7. Farming & animals

| Say | Does |
|---|---|
| `tend the farm` / `harvest the crops` | Harvests mature wheat/carrots/potatoes/beetroot, replants from seeds |
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
| `survive` / `fend for yourself` / `work on your own` | Survival brain on — self-preserves when idle: gets food, keeps a working pickaxe, repairs worn tools, stashes a full pack, keeps wood + a dirt shelter-kit on hand, and **walls itself in at night when monsters are near** (breaks back out at dawn). Add a role for stockpiling too. |
| `take a break` / `wait for orders` | Autonomy off |
| `head home at night` / `work nights` | Night routine on/off |
| `status` / `how are you` | HP, mode, role, auto, items, food, jobs, standing orders |
| `jobs` / `what are you doing` | The queue, in order |
| `help` / `what can you do` | In-game cheat sheet |

## 9. Slash commands

`/assistant` + `spawn · follow · stay · guard · come · stop · home · sethome ·
goto <place|x y z> · mark <place> · gather <what> [n] · mine [level] · deposit ·
withdraw <item> [n] · give <item> [n] · craft <what> · smelt <what> [n] ·
build <structure> · farm · hunt [animal] [n] · shear · breed [animal] ·
herd <animal> [n] · fish [n] · cleanup · sort · enchant [what] · stock <item> ·
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
- **Retreat instinct** — at ~35% HP it disengages, runs home or to you,
  eats back to ~70%, then resumes the paused job automatically.
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
- **Storage memory** — remembers the contents of every chest it touches.
- **Tree replanting** — a sapling goes back on every stump it makes.
- **Worksite lighting & doors** — torches dark work spots; opens/closes
  doors while pathing; mines are torch-lit as dug.
- **Idle backoff** — autonomous work pauses ~2 minutes when an area is dry.
- **Persistence** — name, role, home, waypoints, inventory, equipment,
  standing orders, and toggles all survive relogs.
- **Safety rails** — never digs into lava/water, skips bedrock, handles
  falling gravel, spawns collision-safe (no suffocation).
- **Chat hygiene** — casual chat is never hijacked; addressed messages
  always get a reply; a bad command never breaks the listener.
- **Assistant Spawner block** — craftable (8 iron + 1 diamond); right-click
  to summon your assistant and set its home point.
