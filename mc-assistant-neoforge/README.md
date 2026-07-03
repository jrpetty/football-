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
- **Retreat instinct** — badly hurt, it breaks off, runs home (or to you),
  eats itself back to health, then **resumes the interrupted job queue**.
- **Crafting** — "craft a stone pickaxe", "make planks/sticks/torches/
  bread/chest". Needs a crafting table for tools — and if none is nearby
  it places its own (from 4 planks). Closes the self-maintenance loop.
- **Withdraw & storage memory** — "grab 10 iron from the chest". It
  remembers which chest holds what from every chest it touches.
- **Standing orders** — "keep the chest stocked with 64 logs": it checks
  stock when idle and restocks forever. "stop keeping" cancels.
- **Farming** — "tend the farm": harvests mature wheat/carrots/potatoes/
  beetroot, sweeps the drops, replants from collected seeds.
- **Building** — "build a wall / platform / shelter": places blueprints
  block-by-block from carried blocks (planks, cobble, dirt...).
- **A crew, not a bot** — "spawn a miner named bob" (up to 10). Address
  one by name ("bob, gather stone") or talk normally for the nearest.
  Roles: miner, lumberjack, farmer, builder ("be a farmer").
- **Idle initiative** — "work on your own": when its queue is empty it
  does role work by itself (mine/chop/farm/stock materials + deposit),
  eating and re-equipping as needed, and backs off when an area is
  tapped out. "take a break" stops it. This is the town seed: a farmer,
  a miner, a lumberjack and a builder around one chest run themselves.
