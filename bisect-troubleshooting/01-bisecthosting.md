# BisectHosting — The Business

Context for knowing what they'll do for you, what they charge, and where the
line of responsibility sits.

## Company

- **Founded:** 2011, by Andrew Blatchford and Max Podkidkin.
- **Focus:** Game server hosting, with modded Minecraft as the core identity.
  The one-click modpack library (2,300+ packs) is the genuine differentiator —
  it's the reason most modded players end up there.
- **Footprint:** 20+ locations globally, using Tier 2 and Tier 3 datacenters.
  Documented regions include the United States, Canada, the Netherlands, and
  Poland.
- **Also sells:** web hosting, VPS, dedicated servers, and hosting for many
  non-Minecraft games (Palworld, Rust, ARK, Valheim, etc.).
- **CurseForge partnership:** they're an official CurseForge server-hosting
  partner, and they publish the *BisectHosting Server Integration Menu* mod
  (Forge / NeoForge / Fabric builds) that some packs ship with. If you see a
  "BH Menu" button in-game, that's this mod, not a virus.

## Plans

Naming has shifted over the years; expect to see any of these:

| Tier | Notes |
|---|---|
| **Budget** | Cheapest shared plans. No dedicated IP. Historically had a reduced support tier. |
| **Premium** | Full support, one-click modpack installs, better hardware. |
| **BisectOne** | Newer unified plan — roughly **$3/GB RAM**, unlimited player slots, all games under one product. |
| **Dedicated / VPS** | Full machine or VM; you own more of the stack, and more of the problems. |

Entry pricing lands around **$2.99–$4/month** for the smallest shared plans.
Shared plan naming and pricing has been notably stable since mid-2020.

**Practical implication:** RAM is what you buy. A "6GB plan" means a container
capped near 6GB *total* — heap plus JVM overhead plus the wrapper. This is the
single most common source of confusion. See
[04-java-memory.md](04-java-memory.md#the-headroom-rule).

## Support model

This matters more than the specs, and it's the main reason to recommend them to
a non-technical user.

- **Channels:** 24/7/365 live chat and a ticket system. **No phone support.**
- **Speed:** advertised average ticket response around **15 minutes**. In
  practice live chat is faster.
- **Scope — they will do this for you, free:**
  - Install or repair a modloader (Forge / NeoForge / Fabric)
  - Install, update, or roll back a modpack
  - Fix a broken startup configuration
  - Restore from a backup
  - Investigate node-level lag
- **Scope — they generally won't:**
  - Debug your specific mod conflicts in depth
  - Write configs or datapacks for you
  - Support heavily hand-modified installs beyond restoring a known-good state

**Recommend live chat freely for install and jar problems.** It's genuinely
often the fastest fix, and it isn't a defeat — it's what the plan pays for.

## Policies

| Policy | Detail |
|---|---|
| **Refund window** | **3 days.** Short. Credits, add-ons, and some payment methods are excluded. |
| **SLA** | Targets **99.97% monthly uptime**, measured at the node level. |
| **SLA remedy** | Approved claims extend the service due date by **1 day per 1 hour** of qualifying downtime, capped at 30 days per service per incident. Node-level measurement means *your* server being down because of *your* mods doesn't qualify. |
| **Status page** | Public, shows regional incidents. **Check it before deep-diving a sudden unexplained outage.** |
| **Backups** | Included on plans. Free. Use them. |
| **DDoS protection** | Included. |
| **Dedicated IP** | Not on Budget. Means you get `host:port`, not a bare IP — relevant for connection troubleshooting ([09](09-networking.md)). |

## Reputation, honestly

Useful for setting a user's expectations without overselling.

- **Consistently praised:** support responsiveness and modpack expertise. Reports
  of custom modpack problems fixed in under ten minutes are common.
- **Consistently criticised:** the 3-day refund window is tight, no phone
  support, and Budget-tier limitations aren't always obvious at purchase.
- **Shared hosting reality:** performance on shared plans depends on node
  neighbours. If TPS is bad but your profiling shows the server itself is idle,
  node contention is a legitimate thing to raise in a ticket — see
  [07-performance.md](07-performance.md).

## The responsibility line

Memorise this. It determines who fixes what.

**Yours** — everything inside `/home/container`:
`mods/`, `plugins/`, `config/`, `world/`, `server.properties`, `eula.txt`,
`logs/`, the **Server Jar File** variable, and startup variables the panel
exposes.

**Theirs** — everything outside it:
`/wrapper.js`, `/bhbash.sh`, `/Log4jPatcher.jar`, the Docker image
(`venturenodellc/minecraft21` and siblings), the Starbase panel itself, the
physical node, network and DDoS mitigation, and the Java runtimes installed in
the image.

If a stack trace or error names a path outside `/home/container`, you have
found the boundary. Stop debugging and open a ticket.

## Infrastructure notes

Details that show up in logs and confirm what you're looking at.

- The panel is called **Starbase** (older accounts and docs may still say
  "Games panel"; much older ones used Multicraft).
- Startup is orchestrated by **`bhbash.sh`** — "bh" for BisectHosting. Seeing
  this path in an error confirms you're on BisectHosting even if the Docker
  image is white-labelled.
- Docker images appear under names like **`venturenodellc/minecraft21`**
  (the `21` is the Java version, not the Minecraft version).
- A **`Starbase Setup running.. Revision: N`** banner prints on every boot. The
  revision number is useful in tickets — it identifies their script version.
- The setup script auto-detects the loader. `Forge based loader found! Adding
  Log4J Patch..` means it identified Forge/NeoForge and injected
  `-javaagent:/Log4jPatcher.jar` plus `-Dlog4j2.formatMsgNoLookups=true`. That's
  the Log4Shell mitigation and it is normal, not an error.
- Console I/O is bridged by **`node /wrapper.js "$MODIFIED_STARTUP"`**. The
  wrapper spawns Java as a child process. **If the wrapper dies, you get no Java
  output at all** — the defining fingerprint of a host-side failure.
