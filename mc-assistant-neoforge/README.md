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
| `!come` | Walks to you once. |
| `!gather logs 16` | Finds and chops logs within 16 blocks into its 27-slot pack (also `stone`, `dirt`). |
| `!deposit` | Walks to the nearest chest/barrel and unloads its pack. |
| `!stop` | Cancels whatever it's doing right now and holds. |
| `!status` | HP, mode, carry count, position. |
| `/assistant dismiss` | Sends it away (it drops its pack first). |

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

## Scope (v1)

This port covers the companion core: spawn/ownership, follow/stay/guard
modes, melee combat with creeper avoidance, gather (logs/stone/dirt),
chest deposit, chat + command control, persistent inventory/mode. The Node
bot's bigger systems (crafting, smelting, building blueprints, job queue,
Claude natural-language brain, web dashboard) are not in the mod yet — they
port incrementally on this foundation.
