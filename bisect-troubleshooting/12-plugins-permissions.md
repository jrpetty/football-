# Plugins, Permissions, and Databases

Plugin servers only — Paper, Purpur, Spigot. For mods see
[05-modloaders.md](05-modloaders.md).

## The essential stack

The consensus core. Install these before anything else, and resist the urge to
add thirty plugins on day one — most "mystery" server problems on a young server
are plugin conflicts from over-installing.

| Plugin | Does | Notes |
|---|---|---|
| **LuckPerms** | Permissions and ranks | Industry standard. Web editor. Non-negotiable. |
| **EssentialsX** | 160+ commands: homes, warps, TP, kits, messages, moderation, basic economy | The Swiss army knife |
| **Vault** | API bridge between economy/permissions/chat plugins | Not user-facing; other plugins require it |
| **CoreProtect** | Logs every block and container interaction; rollback | **Your griefing insurance.** Install before you need it. |
| **WorldGuard** + **WorldEdit** | Region protection and terrain editing | WorldGuard needs WorldEdit |

Strong second tier: **Chunky** (pre-generation), **spark** (profiling),
**DiscordSRV** (chat bridge), **GrimAC** (anti-cheat), **Multiverse-Core**
(multiple worlds).

## Installing

**Minecraft Tools → Plugins** browses and installs directly. Preferred — it
handles versions.

Manual: drop the `.jar` in `plugins/` and restart. **Restart, not reload** —
`/reload` is a well-known source of memory leaks and half-initialised plugin
state. Use it for config re-reads at most, never for installs.

Sources: [SpigotMC](https://www.spigotmc.org/resources/),
[Modrinth](https://modrinth.com/plugins), [Hangar](https://hangar.papermc.io/),
[BuiltByBit](https://builtbybit.com) (paid). **Only these.** See
[15-security.md](15-security.md#backdoored-plugins).

## Plugin failure signatures

| Symptom | Cause |
|---|---|
| `Could not load 'plugins/X.jar'` — `UnsupportedClassVersionError` | Plugin needs newer Java |
| `Unknown/missing dependency: Vault` | Install the named dependency |
| `plugin is not compatible with this server version` | Version mismatch — get the matching build |
| Plugin loads, commands don't work | Permissions. See below. |
| `NoClassDefFoundError` referencing another plugin | Load-order or missing soft-dependency |
| Server starts, then hangs at `Preparing spawn area` | A plugin blocking the main thread on startup |
| Two plugins doing the same job | Conflict. Pick one. Two economy or two protection plugins never coexist cleanly. |

**Bisect plugins the same way you bisect mods** — see
[06-crash-reports.md](06-crash-reports.md#bisecting-mods).

## LuckPerms

The mental model: **players inherit from groups; groups inherit from groups;
permissions are nodes that can be true or false.**

```
lp editor                              # web editor link — easiest by far
lp user <player> parent set <group>
lp user <player> permission set <node> true
lp group <group> permission set <node> true
lp group <group> parent add <parent>
lp creategroup <name>
lp listgroups
lp user <player> info
lp verbose on                          # live-log permission checks — the debugger
```

`lp editor` generates a temporary web session. It's dramatically easier than
console for anything non-trivial.

`lp verbose on` is the tool for "why can't they use this command" — it prints
every permission check as it happens, showing the exact node name being tested.

Default groups: `default` (everyone) and `admin`. Build a ladder —
`default → member → trusted → mod → admin` — with each inheriting the one below.

### Permissions vs OP

**Don't op people.** `op` grants permission level 4 — everything, including
`stop`, `op`, and every plugin command that checks for op. It bypasses your
permissions plugin entirely.

Give a `mod` group the specific nodes instead. It's more work once and prevents
the entire class of "my moderator deleted the world" incidents. See
[15-security.md](15-security.md).

## MySQL

**Databases** tab → create. You get host, port, database name, username,
password.

Worth using for: LuckPerms (essential across a network), CoreProtect (large
logs), economy plugins, anything sharing state between servers.

Not worth it for a single small server — flatfile/SQLite is fine and one less
failure mode.

### LuckPerms

`plugins/LuckPerms/config.yml`:

```yaml
storage-method: mysql
data:
  address: <host>:<port>
  database: <database>
  username: <username>
  password: <password>
```

Success looks like `Loading storage provider... [MYSQL]` with no errors after it.

### CoreProtect

`plugins/CoreProtect/config.yml`:

```yaml
use-mysql: true
mysql-host: <host>
mysql-port: <port>
mysql-database: <database>
mysql-username: <username>
mysql-password: <password>
```

### Connection troubleshooting

| Error | Cause |
|---|---|
| `Communications link failure` | Wrong host/port, or the DB isn't reachable from the node |
| `Access denied for user` | Wrong credentials, or the user isn't allowed from this host |
| `Unknown database` | Database name wrong — it's usually prefixed, e.g. `s123_name` |
| `Too many connections` | Pool limit; reduce `maximum-pool-size` |
| Works locally, fails on the server | You used `localhost` — use the panel's host value |

Use the **panel's** host value, not `localhost`. The database usually lives on a
different container or host than your server.

## CoreProtect — because it matters

The plugin you'll be glad you installed before you needed it.

```
/co inspect              # toggle: click blocks to see their history
/co lookup u:<player> t:<time> r:<radius>
/co rollback u:<griefer> t:2h r:50
/co restore u:<griefer> t:2h r:50     # undo a rollback
/co purge t:30d          # trim old data
```

Time format: `2h`, `3d`, `1w`. Radius in blocks; `r:#global` for everywhere.

**Always `lookup` before `rollback`.** Rollback is powerful and blunt — rolling
back a whole region by time undoes legitimate building alongside the griefing.
Scope by player where you can.

Run `/co purge t:60d` periodically or the database grows without bound.

## Config files

| File | Controls |
|---|---|
| `server.properties` | Vanilla settings. All server types. |
| `bukkit.yml` | Spawn limits, tick rates, chunk GC |
| `spigot.yml` | Entity ranges, mob spawn range, view distance overrides |
| `paper-global.yml` | Paper server-wide settings |
| `paper-world-defaults.yml` | Paper per-world defaults — **the performance file** |
| `plugins/<Name>/config.yml` | Per-plugin |

Tuning guidance in [19-config-tuning.md](19-config-tuning.md).

**YAML is whitespace-significant.** A stray tab or lost indent produces a parse
error and the plugin loads defaults — or refuses to load. Validate with a YAML
linter before restarting if you've made a large edit.
