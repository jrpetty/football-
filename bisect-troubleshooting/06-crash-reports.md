# Reading Logs and Crash Reports

## Where the evidence lives

| Path | Contains |
|---|---|
| `logs/latest.log` | **Start here.** Full console output of the most recent run. |
| `logs/<date>-N.log.gz` | Rotated older logs. Gzipped. |
| `crash-reports/crash-*.txt` | Formal crash reports — **only written if the JVM got far enough** |
| `hs_err_pid*.log` | JVM-level hard crash (server root). See [04](04-java-memory.md#reading-hs_err_pidlog). |
| Console tab | Live, but scrollback is limited. The files are authoritative. |

**For startup failures, `latest.log` beats `crash-reports/`.** A server that dies
before the game loop never writes a crash report — which is why "there's no
crash report" is not evidence of anything.

## The cardinal rule

**Read the first error, not the last.**

Java exceptions cascade. One real failure produces dozens of downstream ones. The
console's final lines are usually the *least* informative part.

Method:

1. Open `latest.log`.
2. Search for the **first** `ERROR`, `FATAL`, `Exception`, or `Caused by`.
3. Read **downward** from there to the bottom-most `Caused by:` in that block —
   that's the root.
4. Everything above your starting point is normal startup noise.

## Normal noise — do not chase

These look alarming and mean nothing:

- `WARN` about experimental settings, deprecated APIs, or "unknown recipe"
- `Log4j` / Log4Shell mitigation messages (`formatMsgNoLookups`, `Log4jPatcher`)
- `Forge based loader found!` — a BisectHosting setup-script notice
- `Failed to load datapacks, can't proceed with server load` — *this one is real*,
  it's the exception to the list
- Mixin `INFO` lines during early loading
- `Advanced terminal features are not available`
- Missing optional-dependency notices

## Startup failure signatures

### Missing dependencies

```
Missing or unsupported mandatory dependencies:
    Mod ID: 'jei', Requested by: 'somemod', Expected range: '[15.0,)', Actual version: '[MISSING]'
```

Unambiguous and self-solving: install the named mod at the named version range.

Read carefully — `Requested by` is the mod that *needs* it, `Mod ID` is what's
*missing*. People install the wrong one surprisingly often.

Common invisible dependencies: **Architectury API**, **Cloth Config**, **Kotlin
for Forge**, **Fabric API**, **Balm**, **Collective**, **Resourceful Lib**.

### Mixins

```
Mixin apply for mod somemod failed
org.spongepowered.asm.mixin.injection.throwables.InjectionError: ...
```

A mod tried to patch a class and the patch didn't fit. Meaning: **the mod's
expectations don't match the environment.**

Ranked causes:

1. **Version mismatch** — mod built for a different Minecraft or loader version.
   By far the most common.
2. **Two mods patching the same code** incompatibly.
3. **A masked missing dependency** — this one matters. When early loading fails
   from a missing dependency, mods' access transformers aren't applied but their
   mixins still are. If such a mixin touches the class used to draw the *error
   screen*, you get an `IllegalAccessError` or mixin failure **instead of** the
   real "you're missing a mod" message.

**So: on any mixin error, check for a missing dependency before assuming a mod
conflict.** The mixin error can be a symptom wearing a costume.

Fix order: verify every mod matches your exact MC + loader version → check for
missing dependencies → remove the named mod and test → bisect if still failing.

### `NoSuchMethodError` / `NoSuchFieldError` / `ClassNotFoundException`

Code referencing something that isn't there. Nearly always a **version
mismatch** — a mod compiled against a different build of another mod or the
loader. Update both to matching versions.

### `Could not find or load main class`

The startup command is wrong. Go to [05-modloaders.md](05-modloaders.md).

### Client mods on a server

```
java.lang.NoClassDefFoundError: net/minecraft/client/...
```

A **client-only** mod was installed on the server. Anything touching
`net.minecraft.client` cannot run server-side.

Usual offenders: shaders (Iris, Oculus), rendering optimisers (Sodium, Rubidium,
Embeddium), minimaps (JourneyMap, Xaero's — though these often ship a
server-safe companion), and most UI mods.

Cause: someone uploaded the **client** pack instead of the **server** pack.

## Anatomy of a crash report

```
---- Minecraft Crash Report ----
// <a joke>

Time: 2026-08-04 12:34:56
Description: Exception in server tick loop        ← WHAT was happening

java.lang.NullPointerException: ...               ← THE EXCEPTION
    at com.somemod.SomeClass.method(SomeClass.java:42)   ← FIRST LINE = closest to the bug
    at net.minecraft.server.MinecraftServer...
Caused by: java.lang.IllegalStateException: ...   ← THE ACTUAL ROOT CAUSE
```

Sections worth reading:

| Section | Value |
|---|---|
| `Description:` | What the server was doing. `Exception in server tick loop` = runtime; `Initializing game` = startup. |
| The exception + first stack frames | **Highest value.** The first non-Minecraft package name is usually your culprit. |
| `Caused by:` (bottom-most) | The real root |
| `-- System Details --` | Java version, memory, OS — confirms environment assumptions |
| `Mod List` / `ModLauncher` | Full loaded mod list with versions |
| `-- Affected level --` | Player position and dimension. **Correlate with a corrupt chunk.** |

**Identify the culprit fast:** scan the stack trace top-down for the first
package that isn't `net.minecraft`, `java.`, `sun.`, `cpw.mods`, `net.neoforged`,
or `org.spongepowered`. That's usually the mod at fault.

## Bisecting mods

When nothing is named, binary search. It always works, and it beats guessing.

1. **Download `mods/` as a backup.** Renaming in place is not a backup.
2. Move half out. Start.
3. Still crashes → culprit is in the remaining half. Doesn't → it's in the removed half.
4. Repeat on the failing half.

Roughly `log₂(n)` restarts — **7 for 150 mods.**

Keep these in place every round or you'll manufacture false positives: the
loader's own libraries, Architectury API, Fabric API, Kotlin for Forge, and
anything half your pack depends on.

## Sharing logs

`latest.log` is often megabytes — too big to paste.

Upload to **[mclo.gs](https://mclo.gs)**. It's the community standard, it
auto-highlights known problems, and every support channel accepts it.

**Before uploading, note that logs can contain:** player usernames and UUIDs,
IP addresses of connecting players, server IP and port, plugin config values,
and occasionally database credentials or API tokens printed by a misbehaving
plugin. Skim for secrets before sharing publicly.

## What to collect before asking anyone for help

Have all of this ready and you'll usually get a real answer first reply:

1. `logs/latest.log` (mclo.gs link)
2. The newest `crash-reports/*.txt` if one exists
3. Exit code from the panel
4. Minecraft version, loader, and loader version
5. Modpack name and version, if any
6. **What changed immediately before it broke**
7. Whether it has ever worked
