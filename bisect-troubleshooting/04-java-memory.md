# Java, Memory, and the JVM

## Version matrix

Minimum Java version per Minecraft version. Newer usually works; older never does.

| Minecraft | Java required |
|---|---|
| 1.12 – 1.16.5 | **Java 8+** |
| 1.17 – 1.17.1 | **Java 16+** |
| 1.18 – 1.20.4 | **Java 17+** |
| 1.20.5 – 1.21.x | **Java 21+** |

Wrong version signature:

```
java.lang.UnsupportedClassVersionError: ... has been compiled by a more recent
version of the Java Runtime (class file version 65.0), this version of the
Java Runtime only recognizes class file versions up to 61.0
```

Class file version decoder: **45 + Java version**. So 52=Java 8, 60=Java 16,
61=Java 17, 65=Java 21.

Fix on the **Startup** tab's Java selector. The container prints
`Java Version: (21)` on boot — verify it took.

Caveat: some **older modpacks explicitly require Java 8** and break on newer
runtimes even though the Minecraft version would allow it. 1.7.10 and 1.12.2
packs especially. Match the pack's documentation over the table above.

## The headroom rule

**The single most misunderstood thing in Minecraft hosting.**

`-Xmx` sets the **Java heap**. It is not the process's total memory. The JVM
also needs, outside the heap:

- **Metaspace** — class metadata. Modded servers load tens of thousands of
  classes; this can run 300–500MB+ on a big pack.
- **Thread stacks** — ~1MB per thread, and there are many.
- **GC structures** — the collector's own bookkeeping.
- **Code cache** — JIT-compiled native code.
- **Direct/native buffers** — network I/O, compression, native libraries.

Plus, in a panel container, the **wrapper process itself** (`node`).

Rule of thumb: **leave 1–1.5GB, or ~15–20%, below the container cap.**

| Container | Safe `-Xmx` |
|---|---|
| 2 GB | 1500M |
| 4 GB | 3072M |
| 6 GB | **5120M** |
| 8 GB | 6656M |
| 12 GB | 10240M |
| 16 GB | 13312M |

Worked example of getting it wrong — a real BisectHosting configuration:

```
-Xmx6144M   in a 6738 MiB container   →   594 MiB for everything else
```

That will survive a quiet vanilla server and get OOM-killed by a modded 1.21
pack. **Exit 137.**

## Xms and Xmx

- `-Xms` — initial heap. `-Xmx` — maximum heap.
- Aikar's guidance is to **set them equal** to avoid resize pauses.
- Panels often ship `-Xms128M`. Harmless in practice; the heap grows on demand.
  Don't fight the panel over it unless you're chasing GC pauses.
- **Never set `-Xmx` above the container cap.** The JVM will happily try to use
  memory the container won't give it, and the kernel kills it.

## Aikar's flags

The community-standard G1GC tuning for Minecraft. Genuinely worth applying on
anything non-trivial.

```
-XX:+UseG1GC
-XX:+ParallelRefProcEnabled
-XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions
-XX:+DisableExplicitGC
-XX:+AlwaysPreTouch
-XX:G1NewSizePercent=30
-XX:G1MaxNewSizePercent=40
-XX:G1HeapRegionSize=8M
-XX:G1ReservePercent=20
-XX:G1HeapWastePercent=5
-XX:G1MixedGCCountTarget=4
-XX:InitiatingHeapOccupancyPercent=15
-XX:G1MixedGCLiveThresholdPercent=90
-XX:G1RSetUpdatingPauseTimePercent=5
-XX:SurvivorRatio=32
-XX:+PerfDisableSharedMem
-XX:MaxTenuringThreshold=1
-Dusing.aikars.flags=https://mcflags.emc.gs
-Daikars.new.flags=true
```

**For heaps above 12GB**, Aikar specifies different values — swap these in:

```
-XX:G1NewSizePercent=40
-XX:G1MaxNewSizePercent=50
-XX:G1HeapRegionSize=16M
-XX:G1ReservePercent=15
-XX:InitiatingHeapOccupancyPercent=20
-XX:G1MixedGCCountTarget=8
```

Notes:

- Works on **any** JVM Minecraft server — Vanilla, Paper, Purpur, Spigot, Forge,
  NeoForge, Fabric.
- `-XX:+AlwaysPreTouch` makes the JVM claim the whole heap at startup. Good for
  consistency, but it makes the container look permanently full — don't mistake
  that for a leak.
- On Starbase, these go in the startup variables or `user_jvm_args.txt`
  (Forge/NeoForge). `user_jvm_args.txt` is the cleaner home because jar
  operations don't clobber it.

### More RAM is not always better

Above ~12GB, G1GC pause times grow and a full GC becomes a visible freeze.
Recommended ceiling for most modded servers is **8–12GB**. If you think you need
more than 12GB, you more likely have a leak or a misconfigured chunk-loading mod
— go to [07-performance.md](07-performance.md).

## OOM

### `java.lang.OutOfMemoryError: Java heap space`

The heap genuinely filled. Java caught it and logged it.

1. Raise `-Xmx` — **only if headroom allows**. If you're already at the safe
   ceiling for your plan, the answer is a bigger plan or less load, not a bigger
   number.
2. Reduce load: `view-distance`, `simulation-distance`, entity counts, loaded
   chunks.
3. Hunt a leak (below).

### `java.lang.OutOfMemoryError: GC overhead limit exceeded`

The collector is running constantly and reclaiming almost nothing. Effectively a
slower, more diagnostic-friendly heap exhaustion. Same fixes.

### `java.lang.OutOfMemoryError: Metaspace`

Not heap — class metadata. Distinctive of very large modpacks. Add:

```
-XX:MaxMetaspaceSize=512M
```

and make sure that allocation exists *outside* your `-Xmx` in the container
budget.

### Exit 137 with no `OutOfMemoryError`

The kernel killed the process before Java could react. Almost always the
headroom rule. See [03-exit-codes.md](03-exit-codes.md#exit-137).

## Memory leaks

Signature: memory climbs steadily over hours, doesn't fall after GC, and the
server eventually dies — regardless of player count.

Diagnosis:

1. Watch the panel's memory graph over a few hours. A leak is a staircase that
   never comes down. Normal sawtooth (up, GC, down) is healthy.
2. Run **spark** — `/spark heapsummary` reports what's holding memory by class.
   See [07-performance.md](07-performance.md).
3. Correlate with recent mod changes.

Known mitigations:

- **MemoryLeakFix** / **AllTheLeaks** — community mods patching known leaks in
  Minecraft and common mods. Widely used in big packs; low risk.
- Scheduled nightly restarts via **Schedules**. A workaround, not a fix, but a
  legitimate one for a pack you don't control.

## Reading `hs_err_pid*.log`

Written when the JVM crashes hard (exit 134/139 on the Java side). Found in the
server root. The valuable parts:

| Section | Tells you |
|---|---|
| `# Problematic frame:` | **The failing native frame — the actual culprit** |
| `# SIGSEGV` / `# SIGBUS` etc. | Which signal |
| `Java frames:` | The Java call stack at crash time |
| `Dynamic libraries:` | Loaded native libs — implicates native mods |

If `Problematic frame` names a mod's native library, that mod is your suspect.
If it names the JVM itself (`libjvm.so`), suspect the Java version or genuine
memory corruption — try a different Java build.
