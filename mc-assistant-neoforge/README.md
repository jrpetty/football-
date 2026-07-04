# MC Assistant — NeoForge mod

The in-game companion as a **NeoForge mod jar**: a real entity in your world
that follows you, guards you, gathers resources, and stashes loot — controlled
by chat or commands. Built for **Minecraft 1.21.1** (NeoForge 21.1.x).

> This is the Java port of the [mc-assistant](../mc-assistant) Node.js bot.
> The mod runs *inside* the game, so it works with modded NeoForge servers
> and single player — no separate program, no bot account.

---

## Build the jar

Requires **Java 21** (JDK — from [adoptium.net](https://adoptium.net), pick
Temurin 21). Then, in this folder:

```bash
# Windows
gradlew.bat build

# Mac / Linux
./gradlew build
```

First build downloads the toolchain (a few minutes). The jar lands in:

```
build/libs/mc-assistant-neoforge-0.1.0.jar
```

Drop it in your `mods/` folder (client for single player; server **and**
client for multiplayer, since it adds an entity).

## Use in game

| Command | What happens |
|---|---|
| `/assistant spawn` | Your assistant appears and binds to you as its owner. |
| `!follow` / `/assistant follow` | Trails you (default mode). |
| `!stay` | Holds position (still defends itself). |
| `!guard` | Attacks hostiles that come near it or you. |
| `!come` | Walks to you once, then keeps following. |
| `!gather logs 16` | Finds and chops logs within 16 blocks into its 27-slot pack (also `stone`, `dirt`). |
| `!deposit` | Walks to the nearest chest/barrel and unloads its pack. |
| `!jobs` | Lists the queued jobs, in order. |
| `!stop` | Clears the whole job queue and holds. |
| `!status` | HP, mode, carry count, jobs queued. |
| `go home` / `/assistant home` | Walks back to its home point and holds there. |
| `set home here` / `/assistant sethome` | Makes its current spot the home point. |
| `open your pack` / `/assistant open` | Opens the management screen from anywhere. |
| `spawn` | Summons a fresh assistant (works by voice/chat with none around). |
| `dismiss` / `/assistant dismiss` | Sends it away (drops its pack and gear first). |

### Natural language — just talk to it

You don't need command syntax. Type plain sentences in chat and the
assistant understands, three ways:

- **Bare orders**: `gather 128 logs and deposit it into the chest`
- **By name**: `assistant, get 2 stacks of stone then follow me`
- **Classic `!` commands**: `!gather logs 16` (still works)

Sentences split on **"and" / "then" / commas** into a sequence of queued
jobs. It knows gather verbs (gather/get/mine/chop/collect/fetch/dig),
deposit phrasings ("deposit", "stash it", "put it in the chest"), stack
math ("2 stacks" = 128, "a stack" = 64), amounts up to 1024, and carries
the verb across clauses ("gather 64 logs and 32 stone"). A sentence that
isn't clearly an order is left alone as normal chat.

### Voice control — built in

**Hold `V` (rebindable in Controls → MC Assistant), speak, release.** Your
words are transcribed by an offline speech engine (Vosk) and sent as chat,
so they flow through the same natural-language parser — everything the
assistant can do, present and future, is automatically voice-controllable.

- **Everything you speak is directed at the assistant.** A spoken sentence
  is always sent as an explicit command — say "gather twenty logs" and it
  arrives as `!gather twenty logs`, so the AI always knows you're talking
  to it and always replies. (Typed chat still uses smart detection so it
  won't hijack normal multiplayer conversation.)
- **Spoken numbers work.** Number words are understood: "gather twenty
  logs" = 20, "one hundred twenty eight" = 128, "a stack" = 64, "two
  stacks" = 128, "clear a ten by ten area" = 10×10.
- First use downloads the ~40 MB English model (one time, from the official
  Vosk site into `config/mc_assistant/`); after that it's **fully offline —
  audio never leaves your machine**.
- Push-to-talk only: the mic is open exclusively while the key is held.
- If the engine can't start on your platform, you'll get a chat message
  saying so, and OS dictation into the chat box (**Win+H** / macOS
  double-tap-Fn) still works as a fallback.

**Every capability has voice phrasings** — that's a hard rule of this mod:
spawn ("spawn"), dismiss ("dismiss" / "go away"), open its inventory ("open
your pack"), go home / set home, follow/stay/guard/come/stop, gather,
deposit, jobs, status.

### Job queue

Gather and deposit orders **queue up and run in sequence** — fire off
`!gather logs 16`, `!gather stone 32`, `!deposit` and it works the list
top to bottom, telling you as each one starts and finishes. `!jobs` shows
what's lined up; `!stop` clears the list. A **lone movement/mode** order
(`!follow`, `!stay`, `!guard`, `!come`, or the GUI buttons) takes over
immediately and empties the queue — but as part of a sentence ("gather
logs **then follow me**") it queues and runs in order like everything else.

### Health

The assistant is **mortal**, and you can see it: its name tag shows live
HP (`Assistant 14/20❤`) that turns yellow then red as it drops. It takes
full damage from mobs, lava, and falls, dies at 0 (spilling its whole
inventory and worn gear), and **slowly regenerates** once it's been out of
combat for a few seconds. Your own hits only land at half strength, so you
can't one-shot your companion by accident.

### Assistant Spawner block

Craft an **Assistant Spawner** (8 iron + 1 diamond) and place it. **Right-click
it** to bring your assistant here — it spawns a fresh one if you don't have one,
or teleports your existing one in — and the block becomes its home point. Find
it in the **Functional Blocks** creative tab.

**Right-click the assistant** (as its owner) to open its management screen:

- Move items in and out of its **27-slot backpack**.
- Equip its **armor** (helmet / chestplate / leggings / boots) and hand it
  **tools/weapons** (main hand + off hand) — gear it wears shows on its body
  and counts for real (armor value, attack damage).
- On-screen **buttons**: **Stop**, Follow, Stay, Guard, Deposit, Come.

`/assistant open` opens the same screen; `/assistant stop` is the command
form of the Stop button.

Only the **owner** (whoever spawned it) can command it or open its inventory.
It persists in the world, drops its full inventory **and worn gear** on death,
never despawns, and won't melee-brawl creepers (that fight is unwinnable up
close). Every `!` message gets a reply, so you can always tell it's listening.

## Targeting a different Minecraft version

Edit `gradle.properties` (`minecraft_version` + `neo_version` must match a
real NeoForge release — see neoforged.net) and the two version ranges in
`src/main/resources/META-INF/neoforge.mods.toml`, then rebuild. Mojang API
names shift between majors, so 1.21.2+ may need small code fixes (e.g.
`EntityType.Builder#build` takes a ResourceKey from 1.21.2).

## Scope

This port covers the companion core: spawn/ownership, follow/stay/guard
modes, melee combat with creeper avoidance, gather (logs/stone/dirt), chest
deposit, a **sequential job queue**, **natural-language chat control**
(voice-ready via any speech-to-text), a **visible health/regen system**, a
craftable **Assistant Spawner** block, chat + command control, and persistent
inventory/mode. The Node bot's bigger systems (crafting, smelting, building
blueprints, web dashboard) are not in the mod yet.

## Survival & autonomy systems

- **Eating** — it heals by eating real food from its pack (nutrition =
  hearts). No food means barely-there regen, so keep it fed.
- **Tool intelligence** — it auto-equips the best tool in its pack for the
  block (axe→logs, pickaxe→stone), digs faster with better tools, and
  wears tools out like a player (it warns you when one breaks).
- **Threat-aware retreat** — it won't trade blows in a fight it should lose.
  Badly hurt, or outnumbered with no way to heal (low HP + no food, or swarmed
  while under-armored), it breaks off instead of dying and dropping everything.
  It runs home or to you if it can; solo in the dark it runs *away from the
  nearest monster and toward the brightest ground it can find* — light means
  fewer mobs and a chance to heal — then **resumes the interrupted job queue**.
  Well-armored, it stands and fights; naked, it flees — the same call a player
  makes. This hardens the deep mining the progression drive sends it on.
- **Crafting** — "craft a stone pickaxe", "make planks/sticks/torches/
  bread/chest". Needs a crafting table for tools — and if none is nearby
  it places its own (from 4 planks). Closes the self-maintenance loop.
- **Smelting** — "smelt 16 iron", "smelt logs" (charcoal), "cook the
  beef". It walks to a nearby **furnace, blast furnace, or smoker**, loads
  the input, and tends it while vanilla does the cooking. It uses fuel from
  its pack (coal first, then planks/sticks/logs) **or the furnace's own fuel
  if you've already lit it** — an already-burning furnace works even when
  its pack is empty. It won't waste fuel on a furnace that's still burning,
  tops up when needed, collects the output, and reclaims its materials if
  something's stuck. Craft a furnace (8 cobblestone) or build the smeltery.
- **The iron age** — "gather iron" / "gather coal" (needs the right
  pickaxe — ores follow player tool rules, no pickaxe means no ore), then
  "smelt 8 iron", then "craft an iron pickaxe/axe/sword/helmet/
  chestplate/leggings/boots/shears/bucket". Better tools dig faster, so
  the whole progression loop pays off: mine coal + iron → smelt → iron
  gear → faster everything.
- **Withdraw & storage memory** — "grab 10 iron from the chest". It
  remembers which chest holds what from every chest it touches.
- **Standing orders** — "keep the chest stocked with 64 logs": it checks
  stock when idle and restocks forever. "stop keeping" cancels.
- **Farming** — "tend the farm": harvests mature wheat/carrots/potatoes/
  beetroot, sweeps the drops, replants from collected seeds.
- **Building** — "build a wall / platform / shelter", plus functional 5x5
  buildings: **smeltery** ("build a furnace building" — 3 furnaces along
  the back wall facing the door, 2 chests, crafting table, torch),
  **storage** (4 chests lining the walls), **workshop** (crafting table +
  furnace + chest), and a 3x3 **watchtower** with a real ladder shaft up
  to a walled deck. Every part is placed from its inventory — structural
  blocks from carried planks/cobble/dirt, and furnaces/chests/tables/
  ladders are real items it crafts first (it tells you exactly what's
  missing and how to say the craft order). Nothing is cheated in.
- **A crew, not a bot** — "spawn a miner named bob" (up to 10). Address
  one by name ("bob, gather stone") or talk normally for the nearest.
  Roles: miner, lumberjack, farmer, builder ("be a farmer").
- **Town coordination (the Job Board)** — place a **Job Board** block and
  right-click it to pick the crew's role mix, cycling **Auto → Mining →
  Balanced → Food → Build**. It becomes your town center: every autonomous
  assistant within 64 blocks self-assigns a role around it (slotted stably so
  they don't all pick the same job), and **Auto** rebalances the crew to
  whatever the depot chest beside the board is short on — an empty larder pulls
  someone to farming, low wood to logging, low stone to mining. Divided labour,
  decided by the town, no hand-assigning. Plus the one-shot crew order
  "everyone gather 128 iron", which splits a haul across the whole crew.
- **Survival brain** — "survive" / "fend for yourself": the 'decide' rung
  of autonomy. When idle it looks after itself before any busywork, picking
  the single most pressing action and announcing it: stash a full pack, hunt
  when out of food (it eats to heal), craft a pickaxe from scratch when it has
  none, replace a nearly-broken tool, keep wood on hand — all sourced by the
  recursive planner. This is the end-goal seed: dropped into the world with
  autonomy on, it keeps itself fed, armed, and alive without being told.
- **Night shelter** — the "I need shelter" instinct. When night falls out in
  the open with monsters about, an autonomous assistant drops what it's doing
  and walls itself into a 1x1x2 pocket with whatever full blocks it carries
  (it keeps a dirt kit on hand for exactly this), waits out the night in
  safety, and breaks back out at dawn — reclaiming the blocks. It won't bother
  if it's already under a roof, and it always yields to a real retreat first.
- **Progression drive** — the "thrive" rung. Once survival is covered and it has
  time to spare, an autonomous assistant climbs the tech tree by the single
  highest-value next step, and only reaches for the next tier once the current
  one is stable: **stone-stable** (a stone pickaxe, a furnace it sets down
  itself, torches for light) → **iron-safe** (mines iron, smelts it, forges iron
  armor — which it auto-wears — plus sword, shield, and iron pickaxe, piece by
  piece) → **food-secure** (tends a nearby farm or breeds animals for renewable
  food). The ordering is deliberately risk-adjusted: iron *armor* (the biggest
  survivability jump) comes before diamonds, and renewable food before luxuries.
  Once fully iron-safe it goes on to **diamonds** (deep-mines, forges diamond
  gear) and, opportunistically, **enchants** its gear at a table with banked XP
  and lapis. When fully equipped it settles into maintenance/role work — a
  self-sufficient agent with surplus to share (the on-ramp to multi-AI work).
- **Farming from scratch** — food security is now unconditional: with no farm to
  tend, it bootstraps one — breaks grass for seeds, tills nearby dirt into
  farmland, plants a plot — then tends it forever after.
- **Idle initiative** — with a role set, once survival needs are met it also
  does role work by itself (mine/chop/farm/stock materials + deposit) and
  backs off when an area is tapped out. "take a break" stops it. This is the
  town seed: a farmer, a miner, a lumberjack and a builder around one chest
  run themselves.
- **Real mining** — "dig a mine" / "mine down to level 12": digs an
  actual torch-lit staircase to depth, opens a gallery, chases every ore
  vein it exposes, bridges cavities, and refuses to dig into lava/water.
- **Ranged combat** — with a bow and arrows (craftable) it fights from
  range and *can* take creepers — kiting at 9+ blocks. No bow? It still
  refuses creeper melee.
- **Armor & weapon auto-equip** — wears the best armor in its pack and
  draws the best sword/axe when a fight starts, automatically.
- **Danger callouts** — "CREEPER east of you — move!" It warns you about
  hostiles near you, and "what do you see?" reports everything nearby.
- **Auto-deposit when full** — pack fills mid-gather: it stashes (walking
  back to a remembered chest if none is near) and resumes the same job.
- **Tree replanting** — saplings go back on every stump it makes.
- **Named waypoints** — "remember this spot as the mine", then
  "go to the mine, dig a mine, then go home and deposit" — travel is a
  real queued job that completes on arrival, so chains run in order.
- **Hunting & shearing** — "hunt 3 cows", "shear the sheep" (needs
  shears): meat, leather, feathers (arrows!), wool — drops all collected.
- **"Give me X" & inventory report** — "give me 10 torches" walks over
  and hands them to you; "what are you carrying?" itemizes the pack.
- **Doors & worksite torches** — opens doors while pathing and drops a
  torch when working in the dark.
- **Grave guard** — if you die within ~128 blocks, your nearest assistant
  races to the spot and secures your entire drop. "give me my stuff"
  hands it all back after you respawn.
- **Totem, shield & creeper dodge** — auto-holds a carried totem of
  undying in its off-hand (a real second life), a shield soaks frontal
  hits, and a hissing creeper triggers an instant sprint out of the blast.
- **Patrols** — "patrol between home and the mine": endless guard laps
  between two waypoints, fighting whatever it meets. "stop" ends it.
- **Night routine** — "head home at night": idle workers walk home at
  dusk and resume at dawn. "work nights" turns it off.
- **Clear & flatten** — "clear a 10x10 area": digs the region clean
  (3 high), keeps the drops, never opens lava.
- **Area lighting** — "light up the area": torch grid until nothing can
  spawn nearby.
- **Bridging** — "bridge forward": places floor ahead of itself across
  gaps, rivers, or lava until it reaches solid ground.
- **Breeding & herding** — "breed the cows" (feeds pairs their real
  breeding food), "bring 2 cows home" (leashes strays with real leads,
  delivers to the "pen" waypoint or home, recovers the leads).
- **Fishing** — "catch 5 fish": real rod, real bite times, vanilla-style
  catches. Rod wears out.
- **Tool repair** — "repair your pickaxe": combines two damaged same-type
  tools grindstone-style into one with summed durability.
- **Structure finding** — "find the nearest village": real locator, gives
  distance + direction, saves it as a waypoint so "go to village" works.
- **Cleanup** — "pick up the items around here": sweeps every drop within
  16 blocks into its pack.
- **Go to coordinates** — "meet me at 120 64 -300" / "go to 100 70 -50":
  navigates to raw coords, not just named waypoints. Chains in order.
- **Auto-sort storage** — "sort the storage": consolidates every item type
  into one home chest across nearby chests. Plus "how much iron do we
  have?" — sums the pack and every remembered chest.
- **Fair enchanting** — it banks XP from its own kills, ore, and smelting
  (shown in status), then "enchant your gear" spends it — with lapis — at
  a real enchanting table (efficiency/sharpness/power/protection). Earned,
  not free.
- **Nether expedition** (experimental) — "go to the nether and get
  glowstone": builds and lights an obsidian portal (needs 14 obsidian +
  flint & steel), crosses, gathers (glowstone/quartz/netherrack/soul
  sand/magma/gold), and comes home — with fire resistance and a safe
  landing so it survives. Blaze fortresses aren't included yet.
- **Boat crossing** (experimental prototype) — "boat to 200 63 -400":
  places and boards a carried boat and sails toward the coords. AI vehicle
  control is rough, so it's best-effort — it recovers the boat if beached.
