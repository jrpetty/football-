# Monitoring and Automation

## Scheduled tasks

**Schedules / Automation** in the panel. Cron-style tasks against the server.

Actions: send a console command, start, stop, restart, create a backup.

### Worth setting up

| Schedule | Why |
|---|---|
| **Daily restart** (low-traffic hour) | Clears accumulated memory, resets leaks. **The single most effective reliability measure for modded servers.** |
| **Daily backup** | Non-negotiable |
| **Warning before restart** | `say Server restarting in 5 minutes` at T−5 |
| **Periodic `save-all`** | Extra safety between autosaves |
| **`co purge t:60d` weekly** | Stops the CoreProtect database growing without bound |

A restart warning chain:

```
T−5min   say Restarting in 5 minutes
T−1min   say Restarting in 60 seconds
T−0      stop
```

The daily restart deserves emphasis. It's a workaround rather than a fix, but for
a modpack you don't control and can't patch, a 4am restart converts "crashes
every three days at an unpredictable time" into "restarts nightly when nobody's
on". That's a real win.

### Cron

Panels use standard 5-field cron:

```
minute hour day-of-month month day-of-week

0 4 * * *      daily at 04:00
0 */6 * * *    every 6 hours
30 3 * * 0     Sundays at 03:30
```

**Check whether your panel's cron runs in UTC or local time.** Getting this wrong
schedules your "4am quiet restart" for peak hours.

## Monitoring

### In-panel

The **Console** tab shows live CPU, memory, and disk graphs. Enough for
spot-checks; no history, no alerting.

Read the memory graph shape:

| Shape | Meaning |
|---|---|
| Sawtooth (rises, drops on GC) | **Healthy** |
| Staircase that never drops | [Memory leak](04-java-memory.md#memory-leaks) |
| Flat at the cap | About to be [OOM-killed](03-exit-codes.md#exit-137) |
| Flat at the cap from startup | `-XX:+AlwaysPreTouch` — **not** a leak |

That last row catches people out. AlwaysPreTouch claims the whole heap up front
by design, so the container looks permanently full. Check your flags before
diagnosing a leak.

### External uptime

Panels don't alert you when the server dies. Options:

| Tool | Notes |
|---|---|
| **UptimeRobot** | Free tier, TCP port checks, Discord/email alerts |
| **MCStatusBot** and similar | Discord bots that watch a Minecraft address specifically |
| **Better Stack / Healthchecks.io** | More general, more capable |

Monitor the **game port**, not the panel. TCP check on `host:port` is enough to
know whether players can connect — which is the question that matters.

### Discord

| Tool | Does |
|---|---|
| **DiscordSRV** | The de-facto chat bridge — Minecraft ↔ Discord, plus join/leave and death messages |
| **MCSM / MCStatusBot** | Auto-updating status embed: online, player count, version |
| **Status4Discord** | Server status posts |
| **UptimeRobot Discord integration** | Downtime alerts into a channel |

A status bot in the server's Discord is the cheapest possible win — it removes
the entire genre of "is the server down?" messages, and it tells *you* about
outages before a player does.

## Logs

### Rotation

Minecraft rotates automatically: `latest.log` plus dated `.log.gz` archives.

They accumulate and **eat disk quota**, which on a panel host eventually stops
the world saving. Clear old ones periodically — this is a genuine cause of
mysterious save failures on long-lived servers.

### What to watch

| Pattern | Means |
|---|---|
| `Can't keep up! Is the server overloaded?` | Ticks running long. [07](07-performance.md) |
| `A single server tick took N seconds` | Watchdog territory |
| Repeated `WARN` from one plugin/mod | Something's wrong even if it hasn't crashed |
| Unexpected `op` grants | **Possible compromise.** [15](15-security.md#signs-of-compromise) |
| `Failed to save chunk` | [World corruption or disk](08-world-data.md) |
| Rising `Error` counts over time | Degradation before a crash |

### Sharing

[mclo.gs](https://mclo.gs) — the standard. Auto-detects known problems.

**Skim for secrets first**: player IPs, database credentials, API tokens printed
by misbehaving plugins. See [06](06-crash-reports.md#sharing-logs).

## A maintenance rhythm

| When | Do |
|---|---|
| **Daily** | Automated backup + restart (scheduled, no human) |
| **Weekly** | Skim logs for recurring warnings; check disk usage |
| **Monthly** | Update plugins/mods; download an off-panel backup; `co purge` |
| **Quarterly** | Review permissions and ops; audit installed plugins; test a restore |

**Test a restore.** An untested backup is a hypothesis. The first time you find
out your backups don't restore should not be the day you need them.

## Preventive habits

- **Scheduled restarts** on modded servers, always.
- **Watch the memory graph shape** rather than the instantaneous number.
- **Pre-generate the world** with Chunky, and set a world border.
- **Update deliberately** — never mid-session, always with a fresh backup.
- **Change one thing at a time**, so when something breaks you know what did it.
- **Keep a working config copy** before tuning ([19](19-config-tuning.md)).
- **Document your setup** — versions, plugin list, custom configs. Future-you
  troubleshooting at 1am will not remember.
