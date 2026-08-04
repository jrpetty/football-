# Modpacks

## Families

| Pack | Loader / MC | RAM (server) | Character |
|---|---|---|---|
| **All the Mods 10** | NeoForge 1.21 | **10–12 GB** | Flagship kitchen-sink: tech, magic, storage, exploration |
| **All the Mods 9** | Forge 1.20.1 | 8–10 GB | Previous flagship, very stable |
| **Better MC (BMC5)** | NeoForge 1.21.1 | 6–8 GB | Vanilla+ — Minecraft, just rounder. 2.5M+ downloads |
| **RLCraft** | Forge **1.12.2** | 4–6 GB | Brutal survival — thirst, temperature, real fall damage. Most-downloaded pack on CurseForge (29.5M+) |
| **Create: Above and Beyond** / **Arcane Engineering** | Forge 1.16–1.18 | 6–8 GB | Create-centric engineering progression |
| **DawnCraft** | Forge 1.18.2 | 8–10 GB | RPG/adventure overhaul with bosses and quests |
| **FTB packs** (Skies, Inferno, StoneBlock) | Forge/NeoForge, various | 6–10 GB | Curated, well-documented, quest-driven |
| **Prominence II** | Fabric/NeoForge 1.20.1+ | 6–8 GB | RPG-flavoured, popular |
| **Vault Hunters** | Forge 1.18.2 | 8–10 GB | Roguelike vaults, heavily streamed |
| **Cobblemon** packs | Fabric/NeoForge 1.20+ | 6–8 GB | Pokémon-style, very popular |

RAM figures are **server-side for a handful of players**. Add roughly 1GB per
additional 3–4 concurrent players on heavy packs.

## Sizing

| Pack size | Mods | RAM | Players |
|---|---|---|---|
| Light / vanilla+ | 10–20 | 4–6 GB | ~5 |
| Moderate | 20–50 | 6–8 GB | ~5 |
| Heavy | 50–150 | 8–12 GB | 3–8 |
| Very heavy (ATM-class) | 300+ | 12–16 GB | 5–10 |

Remember [the headroom rule](04-java-memory.md#the-headroom-rule): a "12GB pack"
needs a **plan** above 12GB, because `-Xmx` must sit below the container cap.
Buying exactly 12GB for a 12GB pack is how people end up with exit 137.

And the caveat that saves the most money: **modded Minecraft is more
single-thread CPU bound than RAM bound.** If spark shows one thread pinned while
memory sits comfortable, more RAM changes nothing.

## Installing on Starbase

### One-click (preferred)

**Minecraft Tools → Minecraft Jar → Modpacks** → search → **Install**.

2,300+ packs. Installs the pack *and* the matching loader, correctly. This is
BisectHosting's genuine strength — use it.

### Manual

For packs outside the library:

1. **Back up** `world/` and `mods/`.
2. Get the **server pack** — **not the client pack.** Most CurseForge packs
   publish a separate server download.
3. Upload the zip via **SFTP** (the web File Manager struggles at pack scale).
4. Unarchive in place.
5. Install the matching loader version via Minecraft Tools.
6. Verify **Server Jar File** points at the right target.
7. `eula=true`, then start.

**Never upload a client pack to a server.** It contains client-only mods —
shaders, minimaps, rendering optimisers — that crash a dedicated server. This is
one of the most common modded failures and it produces confusing
`NoClassDefFoundError: net/minecraft/client/...` traces. See
[06](06-crash-reports.md#client-mods-on-a-server).

If no server pack exists, generate one with
[ServerPackCreator](https://github.com/Griefed/ServerPackCreator).

## Updating

Modpack updates are the **highest-risk routine operation** on a modded server.

1. **Back up. Download the backup off-panel.** Not optional.
2. Read the pack's changelog for breaking changes and required world resets.
3. Update via Minecraft Tools where possible.
4. **Update client and server to the same version at the same time.**
5. Start, watch the console, and check for missing-registry warnings.

Common outcomes:

| Symptom | Cause |
|---|---|
| Missing blocks/items as air | A mod was removed; its content is gone from the world |
| `Registry remapping` errors | Mod IDs changed between versions |
| World won't load | Major version jump; may need a fresh world |
| Client can't connect | Client and server pack versions differ |

**Major-version jumps often require a new world.** Say this before someone
updates a six-month base into unloadable rubble.

## Client and server must match

Players need the **same pack, same version, same source**. Mismatch gives a
connect-time rejection listing the offending mods.

Server-side-only mods are fine on the server alone. Client-only mods (shaders,
minimaps, FPS boosters) are fine on the client alone. **Everything that adds
blocks, items, or entities must be on both.**

## Pack-specific gotchas

- **RLCraft is 1.12.2** — needs **Java 8**, not 21. A frequent trap given the
  general "newest Java" advice.
- **Anything pre-1.17** generally wants Java 8; the modern version matrix does
  not apply to old packs. Follow the pack's documentation.
- **Create-based packs** are CPU-heavy rather than RAM-heavy — contraptions tick
  hard.
- **Cobblemon and other data-heavy packs** benefit from higher metaspace.
- **Chunk-loading mods** (quarries, mining machines, chunk loaders) are a common
  hidden cause of runaway load. See [07](07-performance.md#3-chunk-loading).
- **ATM-class packs** need long first-boot times. The
  [watchdog](07-performance.md#the-watchdog) may fire during initial worldgen —
  this is the legitimate case for temporarily raising `max-tick-time`.

## Performance mods worth adding

Server-safe, and often a bigger win than more RAM:

| Mod | Does |
|---|---|
| **FerriteCore** | Cuts memory usage substantially |
| **ModernFix** | Faster startup, lower memory |
| **Canary** / **Radium** | General optimisation (Lithium ports) |
| **AllTheLeaks** / **MemoryLeakFix** | Patches known leaks |
| **Alternate Current** | Faster redstone |
| **Chunky** | Pre-generate to eliminate worldgen lag |

**Not server-safe** — these are client-only and will crash a server: Sodium,
Iris, Oculus, Rubidium, Embeddium, and most rendering or shader mods.
