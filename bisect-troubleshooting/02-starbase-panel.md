# The Starbase Panel

BisectHosting's control panel. Pterodactyl-derived, heavily customised. This is
where every fix in this KB gets applied.

Access it from the client area at `bisecthosting.com` → your service → the panel
link, or directly at their panel domain. Panel credentials are separate from
billing credentials — a common first-contact confusion.

## Tabs and what lives where

| Tab | Contains | You come here for |
|---|---|---|
| **Console** | Live server output, command input, start/stop/restart, resource graphs | Reading crashes as they happen; issuing in-game commands |
| **Files** | Full file manager, upload/download, editor, archive/unarchive | `logs/`, `crash-reports/`, `mods/`, `server.properties`, `eula.txt` |
| **Databases** | MySQL creation and credentials | Plugins needing SQL (LuckPerms, CoreProtect, economy) |
| **Backups** | Create, restore, download, delete | **Before every destructive change** |
| **Network** | Server address, port, allocations | Confirming the real connect address |
| **Startup** | **Server Jar File**, Java version, startup variables, the command template | The single highest-value tab for boot failures |
| **Settings** | SFTP details, server name, **Reinstall** | SFTP for bulk transfers; reinstall as a last resort |
| **Users** | Subusers and permissions | Giving a friend console access without billing access |
| **Schedules / Automation** | Cron-style tasks | Nightly restarts, automated backups |
| **Minecraft Tools** | **The BisectHosting-specific one.** Sub-menus below. | Almost every install/version problem |

## Minecraft Tools — the important one

This is what separates Starbase from stock Pterodactyl, and it's where most
problems are *correctly* fixed. Using these menus is strongly preferred over
hand-editing the **Startup** tab, because the panel rewrites those variables
whenever a jar operation runs — manual edits get silently clobbered.

| Sub-menu | Purpose |
|---|---|
| **Minecraft Jar** / **Modloader** | Install or change server software: Vanilla, Paper, Purpur, Spigot, Forge, NeoForge, Fabric, and modpacks. Sets **Server Jar File** correctly for you. |
| **Plugins** | Browse and install plugins (Bukkit/Spigot/Paper servers only) |
| **Modpacks** | One-click install from the 2,300+ library; also update and version-switch |
| **Instance Manager** | Manage multiple server instances |
| **Player Manager** | Ops, whitelist, bans without touching JSON files |

### Installing or repairing a modloader

1. **Stop the server.** Fully — wait for offline, don't just restart.
2. **Minecraft Tools** → **Minecraft Jar**.
3. **Modloader** → pick **NeoForge** / **Forge** / **Fabric**.
4. Click **Install** next to the version you want; confirm the game version;
   **Next**.
5. Verify on the **Startup** tab that **Server Jar File** changed.
6. Start.

### When the standard install won't work

**Minecraft Tools** → **Minecraft Jar** → **Custom** tab → **Custom
Forge/NeoForge Starter**.

This installs a starter-jar wrapper — the same concept as NeoForged's
[ServerStarterJar](https://github.com/neoforged/ServerStarterJar) — that boots
the loader from a plain `-jar` command. It sidesteps the `@argfile` mismatch
entirely and is the most reliable option when the normal path misbehaves.

There's also **Custom JAR** for uploading your own, e.g. a server pack you
generated locally with the NeoForge installer's "Server Starter Jar" checkbox
ticked.

## Startup tab specifics

The startup command is a **template with variable substitution**. You edit the
variables; the template is usually fixed. A typical resolved command:

```
java -Dlog4j2.formatMsgNoLookups=true -javaagent:/Log4jPatcher.jar \
  -Xms128M -Xmx6144M -Dterminal.jline=false -Dterminal.ansi=true \
  -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  @NeoForge-1.21.1-21.1.248.jar
```

Reading it piece by piece:

| Fragment | Meaning |
|---|---|
| `-Dlog4j2.formatMsgNoLookups=true` | Log4Shell mitigation. Normal. |
| `-javaagent:/Log4jPatcher.jar` | Auto-injected when a Forge-family loader is detected. Normal. |
| `-Xms128M` | Initial heap. Low start is fine. |
| `-Xmx6144M` | **Max heap.** The number that matters. |
| `-Dterminal.jline=false -Dterminal.ansi=true` | Console formatting for the wrapper. |
| `@NeoForge-...jar` | **The launch target — and the usual bug.** See below. |

### The `@` versus `-jar` distinction

Everything in [05-modloaders.md](05-modloaders.md#the-argfile-trap), but the
one-line version, because it's the highest-frequency real-world failure:

- `-jar something.jar` → run this jar. **Correct for:** Vanilla, Paper, Purpur,
  Fabric, starter jars.
- `@something.txt` → read this **plain-text file** as a list of command-line
  arguments. **Correct for:** Forge/NeoForge argfiles.
- `@something.jar` → **always wrong.** Java is being told to parse a binary ZIP
  as text.

## Java version selection

The **Startup** tab usually exposes a Java version selector, and the container
boot prints `Java Version: (21)`.

Set it from [the version matrix](04-java-memory.md#version-matrix). Wrong Java
gives `Unsupported class file major version` — a distinctive, easy win.

## Backups

Free and included. Non-negotiable before:

- any modpack install, update, or version change
- any **Reinstall**
- deleting or regenerating chunks
- bulk mod changes

Backups are **snapshots of the whole server directory**. Restoring reverts
everything, not just the world — so if you've made config fixes since the
backup, re-apply them after restore.

For world-only safety, download `world/` (plus `world_nether/`, `world_the_end/`
on plugin servers) separately through the Files tab.

## Reinstall — read before clicking

**Settings** → **Reinstall Server** re-runs the install script from scratch.

Genuinely useful when the install is structurally broken and jar menu fixes
won't take. But: **it can overwrite or wipe server files.** Download `world/`
and `mods/` first, every time, no exceptions.

## SFTP

Under **Settings**. Use FileZilla or WinSCP for anything bulky — the web File
Manager is slow and unreliable above a few hundred megabytes, which is most
modpacks.

- Host and port: as shown in the panel (not the game port)
- Username: the panel-provided form, usually `user.serverid`
- Password: your **panel** password

## Known panel gotchas

- **Jar menu operations overwrite Startup variables.** Manual edits to **Server
  Jar File** don't survive a subsequent install. Fix via Minecraft Tools instead.
- **A restart is not a stop.** Startup variable and `server.properties` changes
  need a full stop → start.
- **`server.properties` is rewritten on shutdown.** Edit it while the server is
  **stopped**, or the running instance overwrites your changes on exit.
- **The console is a bridge, not the server.** Console disconnects don't
  necessarily mean the server died — check the state indicator and resource
  graphs.
- **`Memory before crash: 0 MiB`** in a crash banner means the JVM never
  allocated. Strong evidence for a pre-Java failure.
- **Auto-restart is throttled.** `Aborting automatic restart, last crash occurred
  less than 60 seconds ago.` is a loop-breaker, not a separate fault.
