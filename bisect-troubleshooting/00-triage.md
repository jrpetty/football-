# Triage — Symptom to Cause

Work top to bottom. The first matching branch is your answer.

## Question 1: Did Java produce any output at all?

Look at the console from the moment it says `Starting server: java ...`.

### No Java output whatsoever

No JVM banner, no `Preparing level`, no mod loading, no crash report. Possibly a
segfault line naming `node` or `wrapper.js`.

**The JVM never started.** Stop looking at mods. Causes, in order of likelihood:

1. **Malformed startup command** — especially `@` in front of a `.jar`. See the
   [argfile trap](05-modloaders.md#the-argfile-trap).
2. **Missing launch target** — the jar or args file named in **Server Jar File**
   doesn't exist. Check the File Manager.
3. **Wrapper or container failure** — segfault in `node`, image pull failure.
   Host-side. Go to [10-escalation.md](10-escalation.md).

Corroborating signal: `Memory before crash: 0 MiB of NNNN MiB`. Zero means
nothing was ever allocated.

### Java started, then died

Go to Question 2.

---

## Question 2: What's the exit code?

The panel prints it. Full table in [03-exit-codes.md](03-exit-codes.md).

| Code | Meaning | Go to |
|---|---|---|
| **0** | Clean shutdown — usually EULA not accepted, or an intentional stop | [05-modloaders.md](05-modloaders.md#eula) |
| **1** | Java-level error — the log has the real message | [06-crash-reports.md](06-crash-reports.md) |
| **134** | SIGABRT — JVM aborted, often native/GC failure | [04-java-memory.md](04-java-memory.md) |
| **137** | SIGKILL — **out of memory, killed by the OS** | [04-java-memory.md](04-java-memory.md#exit-137) |
| **139** | SIGSEGV — segfault, native crash | [03-exit-codes.md](03-exit-codes.md#exit-139) |
| **143** | SIGTERM — graceful stop, or watchdog killed a frozen tick | [07-performance.md](07-performance.md#watchdog) |

---

## Question 3: What does the log actually say?

Open `logs/latest.log` in the File Manager. Search for the **first** occurrence
of `ERROR`, `Exception`, or `Caused by`. Match against these:

### Startup failures

| Log contains | Cause | Fix |
|---|---|---|
| `Unsupported class file major version` | Java too old for this Minecraft version | [Java matrix](04-java-memory.md#version-matrix) |
| `You need to agree to the EULA` | `eula.txt` not accepted | Set `eula=true` |
| `Missing or unsupported mandatory dependencies` | A mod needs another mod | [06](06-crash-reports.md#missing-dependencies) |
| `Mixin apply for mod X failed` | Mod incompatibility — **often a masked missing dependency** | [06](06-crash-reports.md#mixins) |
| `Could not find or load main class` | Wrong jar or wrong startup command | [05](05-modloaders.md) |
| `Error: Could not create the Java Virtual Machine` | Bad JVM flags or heap larger than the container | [04](04-java-memory.md) |
| `java.lang.OutOfMemoryError` | Heap exhausted | [04](04-java-memory.md#oom) |
| `Failed to start the minecraft server` | Generic — read the `Caused by` beneath it | [06](06-crash-reports.md) |
| `Address already in use` | Port conflict, or an old process still running | Restart from the panel, not the console |

### Runtime failures

| Symptom | Cause | Fix |
|---|---|---|
| Server freezes then exit 143 | Watchdog killed a tick over `max-tick-time` | [07](07-performance.md#watchdog) |
| TPS below 18, MSPT above 50 | Genuine performance problem | [07](07-performance.md) |
| Crash entering a specific area | Corrupted chunk | [08](08-world-data.md) |
| `Failed to save chunk` / `Chunk file at ... is missing` | World corruption | [08](08-world-data.md) |
| Memory climbs until exit 137 over hours | Memory leak | [04](04-java-memory.md#leaks) |
| Random disconnects, server stays up | Network / client-side | [09](09-networking.md) |

### Can't connect, but the panel says online

Go straight to [09-networking.md](09-networking.md).

---

## Question 4: Did it ever work?

This one question saves the most time, and it's the one people skip.

| Answer | What it tells you |
|---|---|
| **Never worked since creation** | Setup problem — jar, loader, Java version, startup command |
| **Worked until I changed something** | It's the change. Revert it. Don't theorise. |
| **Worked, changed nothing, now broken** | Suspect: host-side incident, an auto-update, a world corruption from an unclean stop, or a mod that only fails under specific in-game conditions |
| **Works, then dies after N minutes** | Memory leak or a scheduled/triggered event. [04](04-java-memory.md#leaks) and [07](07-performance.md) |
| **Works until a specific player joins** | That player's chunk, inventory, or client mods. [08](08-world-data.md) |

---

## The fast bisect

When the log doesn't name a culprit and you have a modpack, binary search beats
guessing:

1. Back up `mods/` (download it — don't just rename it).
2. Move half the mods out. Start.
3. Crashes? Culprit is in the remaining half. Doesn't? It's in the half you removed.
4. Repeat on the failing half.

~7 restarts isolates one mod out of 150. Keep required dependency mods (the
loader's own libraries, Architectury, Kotlin for Forge, etc.) in place every
round or you'll generate fake failures.

---

## When to stop and escalate

Escalate to BisectHosting support when **any** of these hold:

- The wrapper (`node`, `wrapper.js`) or `bhbash.sh` appears in the failure.
- The container won't pull or start at all.
- Everyone on your node is affected, or their status page shows an incident.
- The panel itself is broken — File Manager won't load, console won't attach.
- You've proven the server files are correct and it still won't boot.

They fix loader and modpack installs for free and answer fast. See
[10-escalation.md](10-escalation.md) for how to write the ticket.
