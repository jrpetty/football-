# Crossplay and Multi-Version Support

## The two editions

| | Java Edition | Bedrock Edition |
|---|---|---|
| **Platforms** | PC only | Console, mobile, Windows 10/11 |
| **Server software** | Vanilla, Paper, Forge, NeoForge, Fabric | Bedrock Dedicated Server, PocketMine, Nukkit |
| **Extensions** | Mods and plugins | Add-ons, behaviour packs |
| **Protocol** | TCP, default **25565** | **UDP**, default **19132** |

They are separate games with incompatible protocols. Crossplay requires
translation.

## Geyser and Floodgate

The standard solution — lets Bedrock clients join a **Java** server.

| Component | Role |
|---|---|
| **Geyser** | Protocol translator. Speaks Bedrock to the client, Java to the server. |
| **Floodgate** | Lets Xbox Live–authenticated Bedrock players join **without a paid Java account** |

Without Floodgate, Bedrock players need to own Java Edition too — which defeats
most of the point.

Supported platforms: Spigot, Paper, Fabric, NeoForge, BungeeCord, Velocity,
ViaProxy. Standalone mode also exists for cases where you can't install plugins
on the server itself.

### Setup

1. Install **Geyser** and **Floodgate** (matching platform builds) into
   `plugins/` or `mods/`.
2. Start once to generate `plugins/Geyser-Spigot/config.yml`.
3. Set `auth-type: floodgate`.
4. **Request a second allocation** from the panel for the Bedrock UDP port.
   Geyser listens on UDP, separate from Java's TCP port. Default 19132.
5. Set that port in Geyser's config.
6. Restart. Bedrock players connect to `<address>:<bedrock-port>`.

**The allocation step is the one people miss.** Without a second allocation
Geyser has no port to bind, and Bedrock players simply can't find the server
while Java players are fine.

### Limitations — set expectations

- **Mods and most complex plugin GUIs don't translate.** Geyser handles vanilla
  mechanics well; custom content generally doesn't cross over.
- Custom inventory GUIs may render as forms or not at all.
- Bedrock players get vanilla-ish behaviour regardless of your mods.
- Some redstone and combat mechanics differ between editions and cannot be
  reconciled.
- Resource packs need a Bedrock-format conversion.

**Geyser is excellent for vanilla/lightly-plugged survival servers and a poor fit
for heavily modded ones.** Say this up front rather than after someone has spent
an evening on it.

### Floodgate usernames

Bedrock players appear with a prefix, `.` by default: `.PlayerName`.

Configurable via `username-prefix`. It exists to prevent collisions with Java
usernames. Remember it when whitelisting or granting permissions — the prefix is
part of the name, and forgetting it is the usual cause of "I whitelisted them and
it still says not whitelisted".

## ViaVersion — cross-version Java

Lets clients on different Minecraft versions join one server.

| Plugin | Enables |
|---|---|
| **ViaVersion** | **Newer** clients → older server |
| **ViaBackwards** | **Older** clients → newer server |
| **ViaRewind** | Very old clients (1.8-era) → modern server |

Install all three for the widest range. Common on large public servers that want
to accept 1.8 PvP clients alongside current versions.

Caveats: newer client features may behave oddly on older servers, and the
translation layer costs some performance. It also interacts with anti-cheat —
GrimAC and similar need configuring for Via, or you get false positives on
version-translated movement.

## Bedrock-native hosting

BisectHosting also sells Bedrock Dedicated Server hosting. Different product,
different rules:

- **Add-ons and behaviour packs**, not plugins or mods
- Config lives in `server.properties` with different keys
- No Bukkit/Spigot plugin ecosystem
- `allowlist.json` instead of `whitelist.json`
- Third-party server software exists (PocketMine-MP, Nukkit) with its own plugin
  ecosystems

If someone wants console players *and* a heavily modded experience, that is not
achievable — the honest answer is to pick one.

## Choosing an approach

| Goal | Approach |
|---|---|
| Java + Bedrock, vanilla-ish survival | Java server + Geyser + Floodgate |
| Bedrock only (console/mobile friends) | Native Bedrock hosting |
| Java only, mixed client versions | ViaVersion + ViaBackwards |
| Heavy mods + console players | **Not possible.** Choose one. |
| Java + Bedrock across a network | Geyser on the **proxy** (Velocity), not per-backend |

## Failure signatures

| Symptom | Cause |
|---|---|
| Bedrock players can't find the server | No second allocation, or wrong UDP port |
| `Unable to connect to world` | Geyser not running — check console for its startup line |
| Bedrock players prompted for a Java account | Floodgate missing or `auth-type` not set to `floodgate` |
| Bedrock joins, then instantly disconnects | Version mismatch between Geyser build and client |
| Whitelist rejects a Bedrock player | Missing the `.` username prefix |
| Java players fine, Bedrock broken after an update | Geyser needs updating — it tracks Bedrock releases closely |

**Geyser updates often**, because Bedrock updates often and breaks protocol
compatibility. A Bedrock-side game update breaking your server is expected
behaviour, not a fault — update Geyser.
