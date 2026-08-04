# Command Reference

**In the panel console, omit the leading `/`.** The slash is only for in-game
chat. `stop`, not `/stop`. Most panels tolerate both, but the console form is
without the slash.

The console runs at **permission level 4** — full access, always.

## Server control

| Command | Does |
|---|---|
| `stop` | **Graceful shutdown with a full world save.** Always use this. |
| `save-all` | Force a save without stopping |
| `save-all flush` | Save and flush to disk immediately |
| `save-off` / `save-on` | Disable/enable auto-save — for external backups |
| `reload` | Reload configs. **Avoid** — a known source of leaks and broken state. |
| `list` | Online players |
| `seed` | World seed |
| `version` | Server version |

**Never use the panel's Kill button unless the server is genuinely wedged.** Kill
is SIGKILL — no save, no flush, and a real chance of
[world corruption](08-world-data.md) if it lands mid-write.

## Players

| Command | Does |
|---|---|
| `op <player>` | Grant level 4. **See the warning below.** |
| `deop <player>` | Remove op |
| `kick <player> [reason]` | Disconnect |
| `ban <player> [reason]` | Ban by name |
| `ban-ip <ip\|player>` | Ban by IP |
| `pardon <player>` | Unban |
| `pardon-ip <ip>` | Un-IP-ban |
| `banlist [players\|ips]` | List bans |
| `whitelist add\|remove <player>` | Whitelist management |
| `whitelist on\|off\|list\|reload` | Whitelist control |

**On `op`:** level 4 is total control — `stop`, `op`, and every plugin command
that checks for op, bypassing your permissions plugin entirely. Use LuckPerms
groups instead. See [12](12-plugins-permissions.md#permissions-vs-op).

Permission levels: **1** bypass spawn protection · **2** most cheat commands ·
**3** player management · **4** everything including `stop`.

## World and gameplay

| Command | Does |
|---|---|
| `time set day\|night\|noon\|midnight` | Set time |
| `weather clear\|rain\|thunder [duration]` | Set weather |
| `difficulty peaceful\|easy\|normal\|hard` | Difficulty |
| `gamemode <mode> [player]` | survival, creative, adventure, spectator |
| `defaultgamemode <mode>` | Mode for new players |
| `tp <player> <target>` | Teleport |
| `tp <player> <x> <y> <z>` | Teleport to coordinates |
| `give <player> <item> [count]` | Give items |
| `setworldspawn [x y z]` | World spawn |
| `spawnpoint <player> [x y z]` | Player spawn |
| `worldborder set <blocks>` | **Set a world border — genuinely useful for performance** |
| `forceload add\|remove <x> <z>` | Force chunks loaded |
| `kill @e[type=item]` | **Clear dropped items — instant lag relief** |

## Gamerules

`gamerule <rule> <value>`

| Rule | Effect |
|---|---|
| `keepInventory` | Keep items on death |
| `doDaylightCycle` | Time progression |
| `doWeatherCycle` | Weather changes |
| `doMobSpawning` | Natural mob spawning |
| `mobGriefing` | Creeper/enderman terrain damage |
| `doFireTick` | Fire spread |
| `randomTickSpeed` | Crop/grass growth rate. Default 3. **Raising this is a common self-inflicted lag source.** |
| `spawnRadius` | Spawn scatter radius |
| `announceAdvancements` | Advancement messages in chat |
| `maxEntityCramming` | Entity stacking limit. Default 24. |
| `disableElytraMovementCheck` | For laggy servers with elytra |
| `playersSleepingPercentage` | % needed to skip night |

`gamerule <rule>` with no value shows the current setting.

## Target selectors

| Selector | Matches |
|---|---|
| `@p` | Nearest player |
| `@a` | All players |
| `@r` | Random player |
| `@e` | All entities |
| `@s` | Self (the executor) |

With filters:

```
kill @e[type=item]                        # all dropped items
kill @e[type=zombie,distance=..50]        # zombies within 50 blocks
tp @a @s                                  # everyone to you
gamemode survival @a[gamemode=creative]   # de-creative everyone
```

**`kill @e` without a type filter kills every entity — including players, item
frames, armour stands, and paintings.** Always filter.

## Diagnostics

| Command | Does |
|---|---|
| `spark tps` | TPS and MSPT |
| `spark profiler start\|stop` | Profiling — see [07](07-performance.md) |
| `spark healthreport` | Full health summary |
| `spark heapsummary` | Memory by class |
| `forge tps` / `neoforge tps` | Per-dimension TPS on Forge/NeoForge |
| `debug start\|stop` | Vanilla profiler |

## Plugin commands

| Command | Plugin |
|---|---|
| `lp editor` | LuckPerms web editor — **the easy path** |
| `lp user <p> parent set <group>` | Assign rank |
| `lp verbose on` | Live permission-check logging |
| `co lookup u:<player> t:<time> r:<radius>` | CoreProtect history |
| `co rollback u:<player> t:2h r:50` | CoreProtect rollback |
| `chunky start` | Pre-generate world |
| `plugins` / `pl` | List loaded plugins |

## Emergency

Server melting down, need it stable *now*:

```
kill @e[type=item]              # clear dropped items — usually the fastest win
gamerule doMobSpawning false    # stop new mobs
kill @e[type=!player]           # nuclear: every non-player entity
save-all flush                  # force a save while you still can
stop                            # clean shutdown
```

`kill @e[type=!player]` destroys item frames, armour stands, paintings, minecarts
and boats too. Last resort.

Then set a `worldborder`, lower `simulation-distance`, and find the actual cause
with spark ([07](07-performance.md)).
