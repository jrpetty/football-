# BisectHosting / Minecraft Server Troubleshooting Knowledge Base

A working reference for diagnosing Minecraft server problems on BisectHosting's
Starbase panel. Built to be read by an AI assistant during live troubleshooting,
and by a human who wants the answer without ten browser tabs.

**Last verified:** August 2026. Panel UIs and version numbers drift — treat menu
names as strong hints, not gospel, and trust what's actually on screen.

## How to use this

Start at **[00-triage.md](00-triage.md)**. It's a decision tree that routes from
a symptom to the right file in two or three questions. Everything else is
reference material you jump into, not documents you read front to back.

The single most important habit this KB encodes: **identify which process died
before theorising about why.** A Minecraft host runs a stack of at least four
layers, and each one fails with a different fingerprint. Most bad
troubleshooting comes from treating a wrapper failure as a mod failure.

## The stack, top to bottom

Knowing this ordering is what makes the rest of the KB usable.

| Layer | What it is | Fails like |
|---|---|---|
| **Mods / plugins** | Your content | Crash report in `crash-reports/`, named mod in the stack trace |
| **Modloader** | NeoForge, Forge, Fabric, Paper | Startup abort, missing dependency screen, mixin error |
| **JVM** | Java 8/17/21 | `OutOfMemoryError`, `Unsupported class file major version`, exit 1 |
| **Wrapper** | `node /wrapper.js` — the panel's console bridge | Segfault, exit 139, **no Java output at all** |
| **Container** | Docker image, `bhbash.sh` startup script | Image pull failure, permission errors, exit 137 from the OOM killer |
| **Node / host** | BisectHosting's physical machine | Everyone on the node is down; check their status page |

The bottom three layers are **BisectHosting's** to fix. You cannot edit
`/wrapper.js` or `/bhbash.sh` — they live outside `/home/container`. Knowing
which side of that line a failure sits on is the difference between a
five-minute fix and an afternoon of deleting innocent mods.

## Index

| File | Covers |
|---|---|
| [00-triage.md](00-triage.md) | Symptom → cause decision tree. **Start here.** |
| [01-bisecthosting.md](01-bisecthosting.md) | Company, plans, support model, SLA, refund window, what they'll do for you |
| [02-starbase-panel.md](02-starbase-panel.md) | Panel anatomy, every tab, where each setting actually lives |
| [03-exit-codes.md](03-exit-codes.md) | Exit code and crash-signature lookup table |
| [04-java-memory.md](04-java-memory.md) | Java version matrix, heap sizing, Aikar's flags, OOM diagnosis |
| [05-modloaders.md](05-modloaders.md) | NeoForge/Forge/Fabric/Paper, correct startup commands, the `@argfile` trap |
| [06-crash-reports.md](06-crash-reports.md) | Reading logs and crash reports, mixins, missing dependencies, bisecting mods |
| [07-performance.md](07-performance.md) | TPS/MSPT, spark profiling, view/simulation distance, lag hunting |
| [08-world-data.md](08-world-data.md) | Corruption, chunk repair, backups, safe recovery |
| [09-networking.md](09-networking.md) | Connection errors, IP/port, online-mode, whitelist |
| [10-escalation.md](10-escalation.md) | Proving it's the host's problem and writing a ticket that gets past tier 1 |
| [99-case-log.md](99-case-log.md) | Solved cases from this session onward — append as we go |

## Ground rules for troubleshooting

1. **Read the first error, not the last.** Java stack traces cascade. The
   bottom-most `Caused by:` is usually the real cause; everything above it is
   collateral.
2. **Zero Java output means Java never ran.** Don't debug mods when the JVM
   didn't start. See [03-exit-codes.md](03-exit-codes.md).
3. **Change one thing at a time.** Otherwise a fix and a new bug cancel out and
   you learn nothing.
4. **Back up before every destructive step.** World folders especially. The
   panel's backups are free — there is no excuse.
5. **`latest.log` beats `crash-reports/`** for startup problems. Crash reports
   only exist if the JVM got far enough to write one.
6. **Version mismatch is the most common modded failure by a wide margin.**
   Loader version, Minecraft version, and mod build all have to agree.
