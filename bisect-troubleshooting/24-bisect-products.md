# BisectHosting's Full Product Line

Beyond Minecraft. Useful for plan-choice questions and for knowing what's
available before recommending a move elsewhere.

## Game servers

**100+ supported games**, with **free game swapping** across titles at any time —
you can repurpose a plan without rebuying. Roughly 21 worldwide locations.

Notable titles and indicative entry pricing:

| Game | From |
|---|---|
| Minecraft (Java & Bedrock) | ~$2.99/mo |
| Terraria | ~$5.99/mo |
| Palworld | ~$17.99–22.49/mo |
| ARK: Survival Evolved | ~$29.99/mo |
| Rust | — |
| Valheim | — |
| Core Keeper | — |
| Satisfactory, 7 Days to Die, Project Zomboid, Garry's Mod, CS2, and many more | — |

Pricing shifts; treat these as order-of-magnitude, and check the site.

### BisectOne

A unified plan: **~$3/GB RAM**, unlimited player slots, all games under one
product, swap between them freely. Simplifies the "which plan" question
considerably — you're buying RAM, not a game.

## Other hosting

| Product | For |
|---|---|
| **VPS** | Full virtual machine, root access. When you've outgrown a game panel. |
| **Dedicated servers** | Whole physical machine |
| **Web hosting** | Websites, forums, store pages for your community |
| **Discord bot hosting** | Persistent bot processes |
| **Mumble hosting** | Voice chat |

**When a VPS beats a game plan:** you need multiple servers on one machine, a
proxy network with firewalled backends
([13](13-proxies-networks.md#firewalling-backends-on-shared-hosting)), custom
software the panel won't run, or full control over the JVM and OS.

**When it doesn't:** you'd be taking on OS patching, security hardening, backups,
and your own panel. For most people the managed plan is correct, and the 24/7
support is a large part of what they're paying for.

## Common infrastructure

Shared across products:

- **21 worldwide locations** — US, Canada, Netherlands, Poland, and more
- **DDoS protection** included
- **24/7/365 support** — live chat and tickets, ~15 min average ticket response.
  No phone.
- **Free backups** on game plans
- **99.97% uptime SLA**, node-level ([01](01-bisecthosting.md#policies))
- **3-day refund window** — short; note it before recommending a purchase

## The modded Minecraft position

Their genuine differentiator, and the reason to recommend them specifically:

- **2,300+ one-click modpacks**
- Official **CurseForge partnership**
- They publish the **BisectHosting Server Integration Menu** mod (Forge /
  NeoForge / Fabric) — the in-game "BH Menu" some packs ship with. Not malware,
  despite looking like it to a suspicious admin.
- Free modpack and modloader installation via support

**If someone's main problem is "I want to play this modpack with friends and
don't want to fight the setup", this is the specific thing BisectHosting is good
at.** For a plain vanilla or Paper server, the modpack library is irrelevant and
they compete on ordinary terms.

## Choosing a plan

| Situation | Plan |
|---|---|
| Vanilla/Paper, ≤5 friends | 2–4 GB |
| Paper + plugins, 10–15 players | 4–6 GB |
| Small modpack (~50 mods), few players | 6 GB |
| Mid modpack (~150 mods) | 8 GB |
| Large modpack (ATM-class) | **12–16 GB** |
| Proxy network | VPS, or several plans |

Two things that save money:

**Buy above the pack's stated requirement.** A "12GB pack" needs a plan larger
than 12GB, because `-Xmx` must sit below the container cap
([04](04-java-memory.md#the-headroom-rule)). Matching exactly is how people end
up with exit 137 on day one.

**Modded Minecraft is more single-thread CPU bound than RAM bound.** If spark
shows one thread pinned while memory sits comfortable, upgrading RAM buys
nothing. Profile before upgrading ([07](07-performance.md)).

## Comparable hosts

For fairness when someone asks whether to move. All have their own docs worth
consulting when BisectHosting's are thin:

**Shockbyte**, **Apex Hosting**, **Nodecraft**, **GGServers**, **Akliz**,
**Aternos** (free, with queues and sleeping servers), **Sparked Host**,
**PebbleHost**.

The honest summary: **BisectHosting's advantage is modded Minecraft convenience
and support responsiveness, not raw price or raw performance.** If someone's
running plain Paper and is price-sensitive, cheaper options exist. If they're
running All the Mods 10 and don't want to think about it, the modpack library and
free install support are worth real money.
