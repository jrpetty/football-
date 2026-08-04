# Escalation — Proving It's the Host

Support queues run on scripts. A ticket that proves the problem is outside the
customer's control skips the script. This file is about writing that ticket.

## The boundary

**Yours** — inside `/home/container`: `mods/`, `plugins/`, `config/`, `world/`,
`server.properties`, `eula.txt`, `logs/`, and the startup variables the panel
exposes.

**Theirs** — outside it: `/wrapper.js`, `/bhbash.sh`, `/Log4jPatcher.jar`, the
Docker image, the Starbase panel, the node, network and DDoS mitigation, and the
Java runtimes in the image.

**If an error names a path outside `/home/container`, you have found the
boundary.** You cannot read those files, let alone fix them.

## Escalate immediately when

- **The wrapper crashes.** Any `node`, `wrapper.js`, or `bhbash.sh` failure.
- **The container won't start.** Image pull failures, permission errors before
  Java.
- **The panel is broken.** File Manager won't load, console won't attach, backups
  fail.
- **The node is affected.** Multiple servers down, or their status page shows an
  incident.
- **Disk or quota errors** you can't resolve by deleting files.
- **You've proven the configuration is correct** and it still won't boot.

## Don't escalate for

These are yours, and a ticket will (correctly) bounce back:

- Mod conflicts and missing dependencies
- Crashes with a named mod in the stack trace
- Wrong Java version — you can change it
- Heap set too high for the plan
- Corrupt worlds from your own crashes
- Whitelist and permissions
- Poor TPS you haven't profiled yet

**Exception worth knowing:** they *will* fix modloader and modpack installs for
free, even though those are technically yours. That's a service they offer, not
an escalation. Use live chat for it without hesitation.

## Writing the ticket

Support reads dozens an hour. Lead with evidence.

### Template

```
Server ID: [from the panel]
Plan: [e.g. 6GB Premium]

PROBLEM
The server process fails before the JVM starts. The panel's node wrapper
is segfaulting.

EVIDENCE
/bhbash.sh: line 1351: 112 Segmentation fault (core dumped) node /wrapper.js "$MODIFIED_STARTUP"
[Panel]: Memory before crash: 0 MiB of 6738 MiB
[Panel]: Exit code: 139

There is no Java output at all — no JVM banner, no mod loading, no crash
report. Memory before crash is 0 MiB, so the JVM never allocated.

WHAT I'VE ALREADY TRIED
- Reinstalled NeoForge 21.1.248 via Minecraft Tools → Minecraft Jar
- Verified Server Jar File points at the correct args file
- Confirmed eula=true
- Confirmed Java 21 is selected
- Reduced -Xmx to 5120M for container headroom

WHY I THINK IT'S HOST-SIDE
wrapper.js and bhbash.sh are outside /home/container, so I can't inspect
or modify them. A SIGSEGV in node is a native crash, not a JavaScript
error. Starbase Setup Revision 1.8.

ASK
Please check the container image and Node wrapper on this node.
```

### What makes this work

| Element | Why it matters |
|---|---|
| **Exact error line, verbatim** | Searchable in their internal tooling |
| **Exit code** | Instantly classifies the failure |
| **"No Java output"** | Rules out every mod-related script response |
| **`Memory before crash: 0 MiB`** | Independently corroborates it |
| **What you already tried** | Skips the entire first round of suggestions |
| **Starbase revision number** | Identifies their script version |
| **A specific ask** | "Please check X" beats "please help" |

## The one line that ends the mod discussion

When support suggests removing mods and you know the JVM never started:

> There is no Java output in the console at all — no JVM banner, no mod loading,
> no crash report — and `Memory before crash` reads `0 MiB`. The JVM never
> allocated memory, so no mod has been loaded yet. The failure is in
> `node /wrapper.js` before Java is reached.

Factual, verifiable from their own logs, and it moves the ticket forward.

## Live chat vs ticket

| Use live chat | Use a ticket |
|---|---|
| Loader / modpack installs | Node-level or recurring issues |
| Quick config fixes | Anything needing engineering escalation |
| "Is this a known incident?" | Billing, refunds, SLA claims |
| Backup restores | Problems with detailed evidence to attach |

Live chat is faster for anything routine; average ticket response is around 15
minutes anyway. There's no phone support.

## SLA claims

- Target: **99.97% monthly uptime**, measured **at the node level**.
- Remedy: **1 day of service credit per 1 hour** of qualifying downtime, capped
  at 30 days per service per incident.
- **Node-level measurement matters.** Your server being down because of your
  mods, your heap setting, or a corrupt world does *not* qualify. The node has to
  have been down.
- Check the status page and note incident times before claiming.
- Refund window is **3 days**, with credits, add-ons, and some payment methods
  excluded. If you're within that window and considering leaving, the clock is
  short.

## Before you escalate — the five-minute self-check

Run through this. It resolves most cases and, when it doesn't, it becomes the
"what I've already tried" section that gets your ticket taken seriously.

1. Full **stop**, then start — not a restart.
2. Check the **status page** for node incidents.
3. Read `logs/latest.log` for the **first** error.
4. Note the **exit code**.
5. Confirm **Java version** matches the Minecraft version.
6. Confirm **`-Xmx`** leaves 1–1.5GB of container headroom.
7. Confirm **Server Jar File** matches the installed loader.
8. Confirm **`eula=true`**.
9. If modded: does the loader version match the Minecraft version?
10. Ask the question that saves the most time: **what changed immediately before
    it broke?**
