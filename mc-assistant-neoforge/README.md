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
| `/assistant dismiss` | Sends it away (it drops its pack first). |

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

**Voice control**: since the assistant understands plain sentences, any
speech-to-text that types into the chat box drives it hands-free — open
chat (`T`), dictate with **Windows voice typing (Win+H)** or **macOS
dictation (double-tap Fn)**, say "gather 128 logs and deposit it into the
chest", hit Enter. Done.

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

## Roadmap (toward autonomy)

The long-term direction, roughly in order:

1. **Self-sufficiency** — keep tools repaired/replaced, eat, retreat and
   heal when hurt, return home (the Spawner block) when idle.
2. **Standing jobs** — repeating orders ("keep the chest stocked with 64
   logs") that re-queue themselves.
3. **Multiple named assistants** — a crew instead of one companion, each
   with its own queue and role.
4. **Building** — blueprint jobs (walls, shelters, layouts you choose).
5. **The town** — assistants that assign each other work: farmers,
   miners, builders sharing storage and expanding a settlement on their
   own. Fully autonomous, self-evolving — the end goal.
