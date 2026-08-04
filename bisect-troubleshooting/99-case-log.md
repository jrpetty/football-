# Case Log

Real cases and how they resolved. Append new ones at the top. Over time this
becomes the highest-value file here, because it records **what actually
happened** rather than what the docs predict.

Format: symptom → evidence → diagnosis → fix → outcome.

---

## Case 001 — NeoForge 1.21.1, wrapper segfault, exit 139

**Date:** 2026-08-04
**Status:** Diagnosed; fix delivered, outcome not yet reported back

### Symptom

Server crash-looped on boot. Two identical attempts, then auto-restart throttled.

### Evidence

```
[Panel]: Starbase Setup running.. Revision: 1.8
[Panel]: Java Version: (21)
[Panel]: Forge based loader found! Adding Log4J Patch..
[Panel]: Starting server: java -Dlog4j2.formatMsgNoLookups=true -javaagent:/Log4jPatcher.jar
         -Xms128M -Xmx6144M -Dterminal.jline=false -Dterminal.ansi=true -Dfile.encoding=UTF-8
         -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 @NeoForge-1.21.1-21.1.248.jar
/bhbash.sh: line 1351:   112 Segmentation fault      (core dumped) node /wrapper.js "$MODIFIED_STARTUP"
[Panel]: Memory before crash: 0 MiB of 6738 MiB
[Panel]: Exit code: 139
[Panel]: Aborting automatic restart, last crash occurred less than 60 seconds ago.
```

Docker image: `venturenodellc/minecraft21`.

### Diagnosis

Two findings, one primary.

**1. The segfault is in `node`, not Java.** Exit 139 = SIGSEGV. The dying process
is `node /wrapper.js` — the panel's console bridge. Corroborating: zero Java
output of any kind, and `Memory before crash: 0 MiB`, meaning the JVM never
allocated. **Not a mod crash, not world corruption, not OOM.**

**2. The startup command is malformed:** `@NeoForge-1.21.1-21.1.248.jar`.

`@` means *argument file* — a plain-text list of arguments. A `.jar` is a binary
ZIP. Java is being told to parse ZIP bytes as command-line text.

Compounding: NeoForge for 1.21.1 ships **no runnable server jar** of that name.
The only distributed jar is the installer. A correct install produces `run.sh`,
`user_jvm_args.txt`, and
`libraries/net/neoforged/neoforge/21.1.248/unix_args.txt`.

Root cause: **Server Jar File** held a `.jar` name while the egg template used
the Forge-family `@argfile` style. Template supplied the `@`, variable supplied a
jar, and the two don't compose.

**Secondary:** `-Xmx6144M` in a 6738 MiB container leaves ~594 MiB for
metaspace, GC, thread stacks, and the wrapper. This is BisectHosting's plan
default rather than a user error, but it's tight enough that a modded 1.21 pack
would likely OOM-kill (exit 137) once the boot problem was fixed.

### Fix delivered

Primary path — let the panel set the variable rather than hand-editing, since
jar operations overwrite manual edits:

**Minecraft Tools** → **Minecraft Jar** → **Modloader** → **NeoForge** →
Install 21.1.248 → confirm game version 1.21.1 → **Next**.

Fallback if that doesn't take:

**Minecraft Tools** → **Minecraft Jar** → **Custom** → **Custom Forge/NeoForge
Starter** — installs a starter-jar wrapper that boots from a plain `-jar`,
removing the `@` from the equation entirely.

Also advised: back up `world/` and `mods/` first, confirm `eula=true`, and watch
for an OOM as a distinct follow-on failure.

Escalation criterion given: if `node` still segfaults with a known-good
configuration, it's host-side — `/wrapper.js` and `/bhbash.sh` are outside
`/home/container`. Ticket line supplied verbatim.

### Notes

- `/bhbash.sh` is the reliable BisectHosting fingerprint even when the Docker
  image is white-labelled (`venturenodellc/*` here).
- `Memory before crash: 0 MiB` proved decisive — it independently corroborated
  "the JVM never started" and rules out the entire mod-hunting branch in one
  line.
- The user's initial framing was a mod/world crash. The log said otherwise. This
  is the KB's central lesson: **identify which process died before theorising
  about why.**

### Open

Outcome not yet confirmed. If it recurs after a clean loader install, escalate as
host-side and record the resolution here.

---

## Template for new cases

```markdown
## Case NNN — one-line summary

**Date:**
**Status:** Resolved / Diagnosed / Open

### Symptom
What the user observed.

### Evidence
Verbatim log lines, exit code, memory reading.

### Diagnosis
Which layer failed and how you know.

### Fix
What was actually done.

### Notes
Anything that would speed up the next case.
```
