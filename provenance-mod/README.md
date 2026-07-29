# Provenance, Antique and Item Legacy

A server-authoritative provenance system for Minecraft **1.21.1 / NeoForge 21.1.x / Java 21**.
Every eligible weapon, tool, shield and piece of armour gets a permanent identity and a
lifetime record: who made it, which company manufactured it, who owns it now, what every
player who ever used it contributed, how often it has been repaired, how far it has
travelled, and which milestones it has earned.

An antique here is valuable because of its genuine age, maker and accomplishments.
**No milestone tier grants damage, protection, efficiency, mining speed or luck.**

---

## Build

```bash
cd provenance-mod
./gradlew build          # or: gradle build
```

The shippable mod jar lands in `neoforge/build/libs/`. The `core` module is bundled into
it via `jarJar`, so the result is a single self-contained file.

> **Network requirement.** Building the jar downloads Minecraft and NeoForge from
> `maven.neoforged.net`. That host must be reachable. See
> [Build status](#build-status-and-what-is-verified) below.

Run the rules-engine tests on their own (needs only Maven Central):

```bash
gradle :core:test
```

---

## Architecture

The system is split in two deliberately.

```
core/       Pure Java. No Minecraft on the classpath.
            All provenance rules: records, contributors, milestones,
            persistence, duplication defence, ownership, distance,
            repair sessions, armour allocation.
            80 unit tests.

neoforge/   The adapter. Translates game events into calls on the core,
            renders the Item History screen, and ships the jar.
```

This is not organisational tidiness. It means the rules that must be exactly right —
that an overall total always equals the sum of its contributors, that a milestone is
awarded once, that a duplicated item cannot inherit a history — are testable without
launching a game, and they are tested.

### Where the data lives

Item components carry **only** an Item Record ID and a rolling anti-duplication token
(`ProvenanceComponents.STAMP`). Statistics, the contributor list and milestone dates are
all server-side, keyed by that id. So:

- A relic with a thousand contributors costs the item stack nothing.
- A modified client cannot edit a counter, because it never holds one.
- Tooltips and the history screen render a read-only snapshot the server computed.

Records are stored as one JSON file per item, sharded into 256 directories by the first
byte of the id, under `<world>/provenance/records/`. Writes are deferred to a background
thread and made atomic (temp file, then move). Nothing touches disk on the server thread.

---

## How the guarantees are enforced

| Requirement | Mechanism |
|---|---|
| Overall totals never diverge from contributor totals | `ItemRecord.record()` is the single write path and updates both under one lock. `verifyOverallMatchesContributors()` proves it, and is exposed as `/provenance admin verify`. |
| Two items can never share an identity | Rolling binding token. Both copies of a duped stack present the same token; the first claimant matches and rotates it, the second is refused and gets a fresh empty record (`Origin.DUPLICATE`). |
| Creation facts are immutable | `crafter`, `company`, `createdEpochMilli` and `origin` are `final` with no setters. Selling, renaming, repairing and upgrading physically cannot reach them. |
| A milestone cannot be forged by renaming | The custom name is a separate field from the earned tier, which is derived only from server-side overall statistics. |
| Milestones grant no power | `MilestoneTier` exposes an index, a display name and an emblem id. There is no numeric a combat or mining calculation could read, and a test asserts no power-shaped member exists. |
| One damage event is not multiplied across four armour pieces | `ArmourDamageAllocator` splits the prevented damage in proportion to each piece's protection and guarantees the parts sum to exactly the whole, remainder included. |
| Mending does not report 40,000 repairs | `RepairSessionTracker` aggregates continuous restoration into sessions; anvil repairs count individually. |
| Teleports do not create false travel | `DistanceAccumulator` drops samples above a discontinuity threshold and re-baselines on dimension change. Off by default, configurable. |
| Cancelled block breaks do not count | The break handler runs at `EventPriority.LOWEST` and does not receive cancelled events. |
| Ownership changes reset nothing | `TransferService` can only reach `ItemRecord.setOwner()`. No statistic, contributor or milestone is reachable from a transfer. |
| A username change does not fork a contributor | Contributions are keyed by UUID; the name is a refreshed display snapshot. |

### Forbidden statistics

There is no `StatKey` for Hunter encounters, Hunter kills, Arena rounds, Arena victories,
Survival Arena usage, server events survived or historical events survived. They are
unrepresentable rather than merely unused, and a test walks the enum to keep it that way.
Ordinary mob kills feed a weapon's normal kill counter. Mob Cards are untouched.

---

## Configuration

Written to `<world>/provenance/provenance-config.json` on first run, with every default
filled in. No recompile is needed for any of it.

```jsonc
{
  "creativeActionsCount": false,
  "teleportDistanceCounts": false,
  "distanceIntervalTicks": 100,
  "repairSessionWindowSeconds": 30,
  "destroyedRecordRetentionDays": 90,
  "reducedAnimations": false,
  "contributorPageSize": 25,
  "minimumDistanceSampleCm": 50,
  "excludedItems": [],
  "itemCategories": { "tinkers:mattock": "pickaxe" },
  "tagCategories": { "c:tools/pickaxe": "pickaxe" },
  "milestones": {
    "pickaxe": {
      "primaryStat": "blocks_mined",
      "thresholds": {
        "initiated": 100, "proven": 1000, "seasoned": 10000,
        "veteran": 100000, "master": 500000,
        "legendary": 1000000, "server_relic": 5000000
      }
    }
  }
}
```

Modded equipment joins by tag or explicit id — the mod never needs to know it exists.

---

## Company integration

`CompanyBridge` is the hook for the existing Shop Mod company system. It is an interface,
not a guess at that mod's internals: Provenance needs a stable company id and a display
name, and only when a craft genuinely happened on the company's behalf.

Until it is wired in, `CompanyBridge.INDEPENDENT` is installed and every craft is honestly
recorded as **Independent**. Attribution deliberately requires the server's chosen
mechanism (company workstation, factory block, or an explicitly active company context) —
membership alone must never attribute a personal craft, because the manufacturer line is
permanent.

```java
CompanyBridge.Holder.set((player, workstationHint) -> {
    var company = ShopMod.activeCompanyContext(player, workstationHint);
    return company == null ? null : new CompanyBridge.Company(company.id(), company.displayName());
});
```

---

## Commands

| Command | Who | Effect |
|---|---|---|
| `/provenance transfer <player>` | Owner, holding the item | Offers ownership. Does not transfer yet. |
| `/provenance accept` | Receiver, holding the item | Completes the transfer once both parties have confirmed. |
| `/provenance cancel` | Either party | Drops a pending offer. |
| `/provenance admin claim <player>` | Operator (level 2) | Assigns an **orphaned** item. Refuses if it already has an owner, so it is not a theft route. |
| `/provenance admin flush` | Operator | Forces a write pass. |
| `/provenance admin verify` | Operator | Checks the held item's overall totals against its contributors. |

Marketplace and Auction Block sales should call
`TransferService.transferViaTrustedSystem(record, buyerId, buyerName)` — the sale itself is
the consent, and history is untouched.

## Inspecting

Press **H** (rebindable) while holding an item, or while hovering one in any inventory.
Right-click is deliberately not used: weapons, shields, tools and rods already use it.

The normal tooltip stays concise. The full record lives on the Item History screen across
four tabs — Overview, Statistics (with an Overall Item / Current Owner toggle),
Milestones and Contributors (searchable, sortable, paginated).

---

## Build status and what is verified

**Verified here, by execution:**

- `./gradlew :core:test` — **80 tests, 0 failures.** The whole rules engine: creation
  records, overall/contributor consistency (including under 8-thread concurrent load),
  milestone awarding and dates, ownership transfer, duplication defence, persistence across
  a full store close and reopen, armour allocation exactness, repair sessions, distance
  policy, contributor paging over 5,000 contributors, and the default threshold tables.
- `./gradlew :core:jar` — clean.
- Gradle wrapper committed and exercised, so `./gradlew` needs no local Gradle install.
- **The adapter was compiled against the real `core` jar.** Full uncapped `javac` run over
  all 15 adapter sources: **372 errors, of which 0 involve `com.provenance.core` and 0 are
  syntax errors.** Every unresolved symbol traces to exactly four root packages —
  `net.minecraft`, `net.neoforged`, `com.mojang`, `org.slf4j`.

  That is a meaningful result, not a formality: it proves the adapter is syntactically
  clean and that every call it makes into the rules engine — method names, arities,
  argument and return types — type-checks against the real compiled core.

**Not verified here, and why:**

The `neoforge` module has **not been fully compiled**, so the jar has not been produced.
This container's egress policy blocks `maven.neoforged.net`:

```
Could not GET '.../net/neoforged/neoform-runtime/1.0.13/neoform-runtime-1.0.13.pom'.
Received status code 403 from server: Forbidden
```

Maven Central is reachable but does **not** carry these artifacts (verified: 404 for
`net/neoforged/neoform-runtime` and `net/neoforged/neoforge`). They exist only on the
blocked host, and the proxy documentation is explicit that policy denials must be reported
rather than routed around.

**What this means practically.** Run `./gradlew build` on any machine that can reach
`maven.neoforged.net` and the jar appears in `neoforge/build/libs/`. What remains unproven
is only whether this code's *understanding of the NeoForge API* matches 21.1 — the
likeliest spots to need a touch-up, in order:

1. `GameplayEvents.creditArmour` — the 1.21.1 damage pipeline (`LivingDamageEvent.Post`,
   `getOriginalDamage()` vs. the `DamageContainer`) is the least stable API surface here.
2. `protectionWeight` — the attribute-modifier iteration API.
3. `ClientEvents.onTooltip` — the `RenderTooltipEvent.GatherComponents` element type.

None of those affect the rules engine, which is where the correctness guarantees live and
which is fully tested.

**If you would rather build it here**, the one-line fix is an allowlist entry for
`maven.neoforged.net` in the Claude GitHub/network settings for this environment. That is
an admin change; ask and I will build and iterate on the jar directly.
