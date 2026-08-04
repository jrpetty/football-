# Modloaders and Server Software

## The landscape

Two incompatible worlds. Mixing them is a frequent beginner error.

### Plugin servers — server-side only, vanilla clients

| Software | Notes |
|---|---|
| **Paper** | The default recommendation. Spigot-compatible plus hundreds of performance fixes. Biggest plugin ecosystem, fastest updates. |
| **Purpur** | Paper fork with extra gameplay config and features. Runs Paper plugins. |
| **Spigot** | Legacy. Paper does everything it does, better. Only for a handful of old setups. |
| **Bukkit / CraftBukkit** | Effectively obsolete. |
| **Folia** | Paper's regionised-threading fork. Very large servers only; most plugins don't support it. |

Players connect with an **unmodified client**. No custom items or blocks.

### Mod loaders — real mods, clients must match

| Loader | Notes |
|---|---|
| **NeoForge** | 2023 community fork of Forge, now the mainstream choice for 1.20.2+. Versioned as `<mcmajor>.<mcminor>.<build>` — e.g. **21.1.248** is a Minecraft **1.21.1** build. |
| **Forge** | The historical standard. Still where older packs live (1.12.2, 1.16.5, 1.18.2, 1.19.2). Diverging from NeoForge over time. |
| **Fabric** | Lightweight, fast to update. Home of Sodium/Lithium-class performance mods. |
| **Quilt** | Fabric fork with extra features; runs most Fabric mods. Small ecosystem. |

**Clients must run the same loader, same Minecraft version, and generally the
same mod set.** Server-side-only mods exist but are the exception.

### Hybrids

**Mohist**, **Magma**, **Arclight**, **Cardboard** — run plugins *and* mods
together. They work, sometimes. They are also the single most common source of
bizarre unreproducible bugs, and most mod authors refuse support for them. Treat
a hybrid in the stack as a live suspect before spending hours elsewhere.

There is **no reliable production compatibility layer** between the Bukkit/Paper
API and the Fabric/Forge mod APIs. Anything claiming otherwise is a hybrid, with
the caveats above.

## Correct startup commands

### Vanilla, Paper, Purpur, Spigot, Fabric

```
java -Xmx4096M -jar server.jar nogui
```

Straightforward `-jar`. Fabric's server launcher jar works the same way.

### NeoForge / Forge — modern (1.17+)

**No runnable server jar exists.** The installer produces a launch layout:

```
run.sh
user_jvm_args.txt
libraries/net/neoforged/neoforge/<version>/unix_args.txt
```

(Forge uses `libraries/net/minecraftforge/forge/<version>/unix_args.txt`.)

Correct command:

```
java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt nogui
```

The `@` prefix means **"read this plain-text file as command-line arguments."**
`unix_args.txt` holds the classpath and main class — it's long, generated, and
you should never edit it. `user_jvm_args.txt` is where **your** JVM flags go
(heap, Aikar's flags); it's the right home because jar operations don't clobber
it.

Windows equivalents use `win_args.txt`. On Linux hosts you want `unix_args.txt`.

### NeoForge / Forge — legacy (≤1.16.5)

Older versions **did** ship a runnable jar:

```
java -Xmx4096M -jar forge-1.12.2-14.23.5.2859-universal.jar nogui
```

This is why old guides say `-jar` and new ones say `@`. Both are right, for
their era.

### Starter jars — the panel-friendly option

[ServerStarterJar](https://github.com/neoforged/ServerStarterJar) (official
NeoForged) reads your existing `run.sh`, extracts the configuration, and
reproduces it — so a plain `-jar` works:

```
java -jar server.jar
java @user_jvm_args.txt -jar server.jar     # with your own JVM flags
```

Built specifically for hosts that only accept a jar filename. On Starbase this
is **Minecraft Tools → Minecraft Jar → Custom → Custom Forge/NeoForge Starter**.

You can also generate one locally: run the NeoForge installer, choose **Install
server**, and tick **Server Starter Jar**.

## The argfile trap

**The highest-frequency real-world startup bug on panel hosts.** Worth
recognising on sight.

```
java ... @NeoForge-1.21.1-21.1.248.jar
```

`@` means *argument file* — a **plain-text** list of arguments. A `.jar` is a
binary ZIP. This tells Java to parse ZIP bytes as command-line text.

**How it happens:** the panel's startup template is the Forge-family `@argfile`
style, and the **Server Jar File** variable got set to a `.jar` name — either by
a half-completed loader install, or by a user typing it in manually. Template
supplies the `@`, user supplies a jar, and they don't compose.

**How it presents:** launch failure with little or no useful Java output. On
BisectHosting it can take the wrapper down with it, producing a `node` segfault
and exit 139 with **zero** Java output — which looks alarming and gets
misdiagnosed as a mod crash or a corrupt world.

**The fix:** don't hand-edit. Reinstall the loader through **Minecraft Tools →
Minecraft Jar → Modloader**, which sets the variable correctly. If that won't
take, use the **Custom Forge/NeoForge Starter**, which removes the `@` from the
equation entirely.

Quick reference:

| Form | Valid? | For |
|---|---|---|
| `-jar server.jar` | ✅ | Vanilla, Paper, Fabric, starter jars |
| `@user_jvm_args.txt` | ✅ | Forge/NeoForge JVM args |
| `@libraries/.../unix_args.txt` | ✅ | Forge/NeoForge launch args |
| `@anything.jar` | ❌ | **Never valid** |
| `-jar installer.jar` as the server | ❌ | Installs, exits 0, no server |

## Installer versus server jar

A recurring confusion, and the reason for spurious exit-0 reports.

NeoForge and Forge distribute an **installer**: `neoforge-21.1.248-installer.jar`.
Running it with `--installServer` generates the launch layout. Setting the
**installer** as your server jar means every "start" just re-installs and exits
cleanly.

Correct manual install:

```
java -jar neoforge-21.1.248-installer.jar --installServer
```

Then launch via the generated `unix_args.txt` path.

## EULA

Every new server, exactly once:

```
You need to agree to the EULA in order to run the server.
Go to eula.txt for more info.
```

Open `eula.txt` in **Files**, change `eula=false` to `eula=true`, save, start.
The file is only created on first boot — so if it's missing, boot once first.

Exit code is **0**, not an error code. It's a clean, intentional stop.

## Version compatibility

The commonest modded failure, by a wide margin. Three things must agree:

1. **Minecraft version** — 1.21.1
2. **Loader version** — NeoForge 21.1.248 (built *for* 1.21.1)
3. **Every mod's build** — each must target 1.21.1 **and** NeoForge

Traps worth knowing:

- **NeoForge versions encode the Minecraft version.** `21.1.x` → 1.21.1.
  `21.0.x` → 1.21. `20.4.x` → 1.20.4. Not interchangeable.
- **1.21, 1.21.1, 1.21.2, 1.21.4 are different versions** for modding purposes.
  A 1.21 mod frequently won't load on 1.21.1.
- **Forge mods don't run on NeoForge** (and vice versa) without a compatibility
  layer, despite the shared ancestry. The divergence widens each release.
- **Fabric mods never run on Forge/NeoForge.** Different API entirely.
- **Client and server mod lists must match** for anything with content. Mismatch
  gives connection-time rejections — see [09-networking.md](09-networking.md).

## Modpacks on Starbase

**Minecraft Tools → Minecraft Jar → Modpacks** installs from the 2,300+ library
in one click, correctly, including the loader. Strongly preferred over manual
assembly.

For a pack **not** in the library:

1. Get the **server pack** — not the client pack. Most CurseForge packs publish a
   separate server download. If none exists, generate one with **ServerPackCreator**.
2. Back up first.
3. Upload the server pack zip via SFTP (the web File Manager struggles with pack-sized files).
4. Unarchive in place.
5. Install the matching loader version via Minecraft Tools.
6. Verify **Server Jar File** points at the right target.
7. Accept the EULA, then start.

**Never upload a client pack to a server.** It contains client-only mods
(shaders, minimaps, rendering optimisations) that crash on a dedicated server —
a very common and very confusing failure mode.
