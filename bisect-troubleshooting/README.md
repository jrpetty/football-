# BisectHosting / Minecraft Server Knowledge Base

A working reference for running and troubleshooting Minecraft servers on
BisectHosting's Starbase panel. Built to be read by an AI assistant during live
troubleshooting, and by a human who wants the answer without ten browser tabs.

28 files, ~24,600 words. **Last verified: August 2026.** Panel UIs, prices, and
version numbers drift — treat menu names as strong hints, not gospel, and trust
what's actually on screen.

## How to use this

**Something is broken →** start at **[00-triage.md](00-triage.md)**. It routes
from symptom to cause in two or three questions.

**You want to understand or build something →** jump to the relevant file below.

Everything here is reference material you jump into, not documents you read
front to back.

## The one idea that matters

**Identify which process died before theorising about why.**

A Minecraft host runs a stack of at least six layers, and each fails with a
different fingerprint. Most bad troubleshooting comes from treating a wrapper
failure as a mod failure.

| Layer | What it is | Fails like |
|---|---|---|
| **Mods / plugins** | Your content | Crash report, named mod in the stack trace |
| **Modloader** | NeoForge, Forge, Fabric, Paper | Startup abort, missing dependency, mixin error |
| **JVM** | Java 8/17/21 | `OutOfMemoryError`, `Unsupported class file major version` |
| **Wrapper** | `node /wrapper.js` — the console bridge | Segfault, exit 139, **no Java output at all** |
| **Container** | Docker image, `bhbash.sh` | Image pull failure, exit 137 from the OOM killer |
| **Node / host** | The physical machine | Everyone on the node is down |

The bottom three are **BisectHosting's** to fix. You cannot edit `/wrapper.js` or
`/bhbash.sh` — they live outside `/home/container`. Knowing which side of that
line a failure sits on is the difference between a five-minute fix and an
afternoon of deleting innocent mods.

## Index

### Start here
| File | Covers |
|---|---|
| **[00-triage.md](00-triage.md)** | **Symptom → cause decision tree** |

### The host
| File | Covers |
|---|---|
| [01-bisecthosting.md](01-bisecthosting.md) | Company, plans, support scope, SLA, refund window, the responsibility line |
| [02-starbase-panel.md](02-starbase-panel.md) | Every tab, where each setting lives, panel gotchas |
| [11-platform-pterodactyl.md](11-platform-pterodactyl.md) | Pterodactyl/Wings/Docker internals, cgroups, eggs, allocations |
| [24-bisect-products.md](24-bisect-products.md) | Full product line, plan choice, comparable hosts |

### Diagnosing failures
| File | Covers |
|---|---|
| [03-exit-codes.md](03-exit-codes.md) | Exit code and crash-signature lookup |
| [04-java-memory.md](04-java-memory.md) | Java version matrix, the headroom rule, Aikar's flags, OOM, leaks |
| [06-crash-reports.md](06-crash-reports.md) | Reading logs, mixins, missing deps, bisecting |
| [07-performance.md](07-performance.md) | TPS/MSPT, spark, view/simulation distance, the watchdog |
| [08-world-data.md](08-world-data.md) | Corruption, chunk repair, backups, safe recovery |
| [09-networking.md](09-networking.md) | Connection errors, `online-mode`, whitelist |
| [10-escalation.md](10-escalation.md) | Proving it's host-side; ticket template |

### Server software
| File | Covers |
|---|---|
| [05-modloaders.md](05-modloaders.md) | NeoForge/Forge/Fabric/Paper, startup commands, the `@argfile` trap |
| [12-plugins-permissions.md](12-plugins-permissions.md) | Essential plugins, LuckPerms, CoreProtect, MySQL |
| [13-proxies-networks.md](13-proxies-networks.md) | Velocity/BungeeCord, IP forwarding, the impersonation trap |
| [14-crossplay-versions.md](14-crossplay-versions.md) | Geyser/Floodgate, ViaVersion, Bedrock |
| [16-modpacks.md](16-modpacks.md) | Pack families, RAM sizing, install and update |
| [22-content-packs.md](22-content-packs.md) | Resource packs, datapacks, SHA-1 |

### Running it well
| File | Covers |
|---|---|
| [15-security.md](15-security.md) | Hardening, exploits, backdoors, anti-cheat, compromise response |
| [17-domains-dns.md](17-domains-dns.md) | A and SRV records, propagation, port hiding |
| [18-commands.md](18-commands.md) | Console command reference, gamerules, selectors, emergency commands |
| [19-config-tuning.md](19-config-tuning.md) | Paper/Spigot/Bukkit performance settings |
| [20-migration.md](20-migration.md) | Moving hosts or software without losing data |
| [23-monitoring-automation.md](23-monitoring-automation.md) | Schedules, uptime alerts, Discord, maintenance rhythm |

### Building
| File | Covers |
|---|---|
| [21-development.md](21-development.md) | Writing Paper plugins and NeoForge mods |

### Records
| File | Covers |
|---|---|
| [99-case-log.md](99-case-log.md) | Solved cases — append as we go |
| [SOURCES.md](SOURCES.md) | Every source, for re-verification |

## Ground rules

1. **Read the first error, not the last.** Java stack traces cascade. The
   bottom-most `Caused by:` is the real cause; everything above is collateral.
2. **Zero Java output means Java never ran.** Don't debug mods when the JVM
   didn't start.
3. **Change one thing at a time.** Otherwise a fix and a new bug cancel out and
   you learn nothing.
4. **Back up before every destructive step.** Download it — a copy on the same
   server is not a backup.
5. **`latest.log` beats `crash-reports/`** for startup problems. Crash reports
   only exist if the JVM got far enough to write one.
6. **Version mismatch is the most common modded failure by a wide margin.**
   Loader version, Minecraft version, and mod build must all agree.
7. **Ask "did it ever work?" and "what changed?"** first. These two questions
   resolve more cases than any log analysis.

## Highest-value facts

Things that come up constantly and are easy to get wrong:

- **`-Xmx` is heap only.** Metaspace, thread stacks, GC structures and the
  wrapper live outside it. Leave 1–1.5GB of container headroom or the kernel
  OOM-kills you. → [04](04-java-memory.md#the-headroom-rule)
- **`@file` means argument file, not jar.** `@something.jar` is never valid.
  → [05](05-modloaders.md#the-argfile-trap)
- **Exit 137 = OOM-killed. 139 = segfault. 143 = watchdog or clean stop.**
  → [03](03-exit-codes.md)
- **1.20.5+ needs Java 21.** 1.18–1.20.4 needs 17. Old packs may need Java 8.
  → [04](04-java-memory.md#version-matrix)
- **Mixin errors are often masked missing dependencies.** Check deps first.
  → [06](06-crash-reports.md#mixins)
- **`simulation-distance` is where the CPU goes**, not `view-distance`.
  → [07](07-performance.md)
- **Modded Minecraft is single-thread CPU bound more than RAM bound.** More RAM
  often changes nothing. → [07](07-performance.md#baseline-expectations)
- **BungeeCord legacy IP forwarding lets anyone impersonate anyone** if backends
  aren't firewalled. Use Velocity modern forwarding.
  → [13](13-proxies-networks.md#ip-forwarding--and-the-security-trap)
- **Never upload a client modpack to a server.** → [16](16-modpacks.md)
- **Binary search finds one bad mod in 150 in ~7 restarts.**
  → [06](06-crash-reports.md#bisecting-mods)
