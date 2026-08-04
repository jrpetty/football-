# Exit Codes and Crash Signatures

## How Unix exit codes encode signals

When a process is killed by a signal, its exit code is **128 + the signal
number**. This is the whole trick:

| Signal | Number | Exit code | Name |
|---|---|---|---|
| SIGABRT | 6 | 134 | Abort |
| SIGKILL | 9 | **137** | Killed — cannot be caught or ignored |
| SIGSEGV | 11 | **139** | Segmentation fault |
| SIGTERM | 15 | **143** | Terminate — graceful, catchable |

Anything under 128 is the program's own chosen exit status.

## Lookup table

| Code | Signal | What actually happened | Whose problem |
|---|---|---|---|
| **0** | — | Clean exit. EULA not accepted, intentional stop, or an installer finishing. | Yours (usually trivial) |
| **1** | — | Java-level error. **The log has the real message** — this code alone tells you nothing. | Yours |
| **127** | — | Command not found. Broken startup command or missing binary. | Split — check your command first |
| **134** | SIGABRT | JVM aborted itself. Native library failure, fatal GC error, or an assertion. Look for `hs_err_pid*.log`. | Usually yours (mods with native code) |
| **137** | SIGKILL | **Out of memory.** The kernel OOM killer terminated the process. Container exceeded its memory cap. | Yours (heap sizing) or theirs (node pressure) |
| **139** | SIGSEGV | Segmentation fault — invalid memory access at native level. | Depends on *which process* segfaulted |
| **143** | SIGTERM | Graceful stop request. Normal on a manual stop. **Unprompted 143s usually mean the watchdog fired** on a tick exceeding `max-tick-time`. | Yours |

## Exit 137 — the most common crash

The kernel killed the JVM because the container hit its memory ceiling.

Confirm by checking the panel's memory graph immediately before the crash — it
will be pinned at the cap.

Causes ranked:

1. **`-Xmx` set too close to the container limit.** No headroom for JVM overhead.
   This is the big one. See [the headroom rule](04-java-memory.md#the-headroom-rule).
2. **Genuine memory pressure** — too many chunks, entities, or players for the
   plan.
3. **A memory leak** — climbs steadily over hours regardless of activity.
4. **Node-level pressure** — rare, but real on shared hosting.

Note the asymmetry with `OutOfMemoryError`: an OOM *error* means the **JVM's own
heap** filled and Java threw an exception it can log. Exit **137** means the
**OS** killed the process — Java never got the chance to complain. If you're
seeing 137 with no `OutOfMemoryError` in the log, that's the tell.

## Exit 139 — segfault

Java is memory-safe. A segfault means something native failed. **The critical
question is which process segfaulted.**

### Java segfaulted

You'll see substantial Java output first, then the crash. Often an
`hs_err_pid<number>.log` file appears in the server directory — that file names
the failing frame.

Causes: native-code mods, a broken or mismatched JVM, corrupt jars.

Fix: read `hs_err_pid*.log`, note the `Problematic frame`, remove the implicated
mod, try a different Java version.

### The wrapper segfaulted — the BisectHosting case

```
/bhbash.sh: line NNNN: 112 Segmentation fault (core dumped) node /wrapper.js "$MODIFIED_STARTUP"
```

**No Java output at all.** `Memory before crash: 0 MiB`.

This is `node` — the panel's console bridge — dying natively before or while
spawning Java. JavaScript errors throw stack traces; they don't segfault. A
SIGSEGV in `node` means the Node binary itself crashed.

Causes:

1. **A malformed startup command handed to the wrapper.** Check for the
   [`@`-on-a-jar bug](05-modloaders.md#the-argfile-trap) first — it's free to
   check and it's the usual culprit.
2. **Broken container image or Node binary.** Host-side.

Fix: correct the startup command via **Minecraft Tools**. If it still segfaults
with a known-good configuration, it is **definitionally BisectHosting's** —
`/wrapper.js` and `/bhbash.sh` are outside your file access. Go to
[10-escalation.md](10-escalation.md).

## Exit 143 — SIGTERM

Normal when you stopped the server. Investigate when it's unprompted.

The usual cause is the **watchdog**: `server.properties` sets `max-tick-time`
(default `60000` ms). If a single tick exceeds it, the watchdog concludes the
server has hung and terminates it.

Log signature:

```
A single server tick took 60.00 seconds (should be max 0.05)
Considering it to be crashed, server will forcibly shutdown.
```

This is a **symptom, not a cause** — something made a tick take 60 seconds.
Common triggers: world generation on a slow node, a huge chunk load, a mod
deadlock, or thrashing GC from a nearly-full heap.

**Don't reflexively disable the watchdog.** It exists to stop a wedged server
from sitting there consuming resources. Setting `max-tick-time=-1` disables it
and is legitimate for *known-slow* operations like first-boot worldgen on a
heavy modpack — but it converts "server restarts" into "server hangs forever",
which is worse for diagnosis. Raise it temporarily, fix the cause, put it back.

See [07-performance.md](07-performance.md#watchdog).

## Exit 0 with no server

The server started, decided it had nothing to do, and quit cleanly.

Almost always:

```
You need to agree to the EULA in order to run the server.
```

Open `eula.txt`, set `eula=true`, start again. The file is generated on first
boot, so this is expected exactly once per new server.

Also seen when an **installer** jar was set as the launch target — it installs,
prints success, and exits 0. That's not a crash; it means your **Server Jar
File** points at the installer instead of the server.

## Reading crash banners

BisectHosting prints a block on crash. What each line is worth:

| Line | Diagnostic value |
|---|---|
| `Server marked as offline...` | None — just state |
| `Detected server process in a crashed state!` | None — just an alert |
| `The server crashed. Please scroll up...` | None |
| `Memory before crash: N MiB of M MiB` | **High.** `0` = JVM never ran. `M` ≈ `N` = OOM. |
| `Exit code: N` | **High.** Use the table above. |
| `Aborting automatic restart, last crash occurred less than 60 seconds ago.` | Loop-breaker. Means it crashed twice fast — not a separate fault. |
