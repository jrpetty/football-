# Provenance, Antique and Item Legacy

A server-authoritative provenance system for Minecraft **1.21.1 / NeoForge 21.1.x / Java 21**.
Every eligible weapon, tool and piece of armour gets a permanent identity and a
lifetime record: who made it, which company manufactured it, who owns it now, what every
player who ever used it contributed, how often it has been repaired, how far it has
travelled, and which milestones it has earned.

An antique here is valuable because of its genuine age, maker and accomplishments.
**No milestone tier grants damage, protection, efficiency, mining speed or luck.**

---

## Build

```bash
cd provenance-mod
./gradlew build
```

The shippable mod jar lands in `neoforge/build/libs/`. The `core` module's classes are
embedded directly, so the result is a single self-contained file.

Every push also builds in CI (`.github/workflows/build-provenance-mod.yml`): the
rules-engine tests run first, then the jar is built and published to the
`provenance-latest` release.

Run the rules-engine tests on their own (needs only Maven Central):

```bash
./gradlew :core:test
```

---

## Architecture

The system is split in two deliberately.

```
core/       Pure Java. No Minecraft on the classpath.
            All provenance rules: records, contributors, milestones,
            persistence, duplication defence, ownership, distance,
            repair sessions, armour allocation.
            99 unit tests.

neoforge/   The adapter. Translates game events into calls on the core,
            renders the Item History screen, and ships the jar.
```

This is not organisational tidiness. It means the rules that must be exactly right —
that an overall total always equals the sum of its contributors, that a milestone is
awarded once, that a duplicated item cannot inherit a history — are testable without
launching a game, and they are tested.

### Where the data lives

Item components carry an Item Record ID with a rolling anti-duplication token
(`ProvenanceComponents.STAMP`), plus a tiny display summary for the tooltip — tier,
maker, one number (`ProvenanceComponents.SUMMARY`) — refreshed at most once a minute and
never trusted on the way back. Statistics, the contributor list and milestone dates are
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
| Two items can never share an identity | Rolling binding token, checked and rotated at custody moments (login, repair, transfer). Both copies of a duped stack present the same token; the first claimant rotates it, the second gets a fresh empty record that remembers its parent — `/provenance admin restore` undoes a false positive. |
| Creation facts are immutable | `crafter`, `company`, `createdEpochMilli` and `origin` are `final` with no setters. Selling, renaming, repairing and upgrading physically cannot reach them. |
| A milestone cannot be forged by renaming | The custom name is a separate field from the earned tier, which is derived only from server-side overall statistics. |
| Milestones grant no power | `MilestoneTier` exposes an index, a display name and an emblem id. There is no numeric a combat or mining calculation could read, and a test asserts no power-shaped member exists. |
| One damage event is not multiplied across four armour pieces | `ArmourDamageAllocator` splits the prevented damage in proportion to each piece's protection and guarantees the parts sum to exactly the whole, remainder included. |
| Mending does not report 40,000 repairs | Mending does not count as a repair at all, by design. Anvil and smithing repairs count individually, attributed to whoever performed them. |
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
  "usageFlushIntervalSeconds": 60,
  "excludedItems": [],
  "itemCategories": { "tinkers:mattock": "pickaxe" },
  "tagCategories": { "c:tools/pickaxe": "pickaxe" },
  "manufacturers": { "tacz:ak47": "Kalashnikov Concern", "tacz:*": "Armoury Issue" },
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

### Equipment that arrives already made

Not everything is crafted where this mod can see it. A gun handed out by a kit, bought from
a shop, pulled from a crate or given by command has no crafter this server can honestly
name, so the crafter line stays **Unknown** — inventing one would break the only guarantee
the system has.

`manufacturers` fills in the half that *is* knowable. It maps an item id to the body that
makes that model, and populates the **Manufactured by** line only. Keys are exact ids, or
`namespace:*` for a whole mod; an exact id wins over a wildcard. For firearms the key is
the specific gun — `tacz:ak47`, not `tacz:modern_kinetic_gun`, because every gun in that
mod shares the one registry entry.

For a one-off — a founder's rifle, a tournament prize — `/provenance admin register` stamps
a real maker onto the item before it is first used. It refuses an item that already has a
record, because creation facts are written once.

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
| `/provenance admin restore` | Operator, holding the item | Undoes a false-positive duplication fork, giving the item its original history back. |
| `/provenance admin register <maker> [manufacturer]` | Operator, holding the item | Gives a not-yet-tracked item a real creation record. Refuses anything that already has one. |
| `/provenance admin stats` | Operator | Store, buffer and tuning numbers: resident/dirty records, flush size and duration, records on disk. |

Marketplace and Auction Block sales should call
`TransferService.transferViaTrustedSystem(record, buyerId, buyerName)` — the sale itself is
the consent, and history is untouched.

## Inspecting

Press **H** (rebindable) while holding an item, or while hovering one in any inventory.
Right-click is deliberately not used: weapons, shields, tools and rods already use it.

The tooltip shows the earned tier with its emblem, the maker, and the category's
defining statistic — refreshed at most once a minute, so heavy mining can lag the number
slightly; tier changes appear instantly. The full record lives on the Item History screen
across four tabs — Overview, Statistics (colour-coded by stat family, with an Overall
Item / Current Owner toggle), Milestones and Contributors (searchable, sortable,
paginated).

---

## Verification status

**Verified by execution, on every push:**

- `./gradlew :core:test` — **99 tests, 0 failures.** The whole rules engine: creation
  records, overall/contributor consistency under 8-thread concurrent load, milestone
  awarding and dates (including one-at-a-time threshold crossing), ownership transfer,
  duplication defence with recoverable forks, persistence across restart, retention
  sweeps, despawn purging, armour allocation exactness, repair sessions, distance policy,
  usage buffering, and contributor paging over 5,000 entries.
- The full mod jar compiles and builds in CI against NeoForge 21.1.77.

**Verified in game, so far:** the Item History screen opens and renders (Overview and
Milestones confirmed against screenshots and corrected); legacy adoption, milestone
progress maths and the honest-Unknown maker behaviour all observed working.

**Not yet verified in game:** the Statistics and Contributors tabs rendered, the despawn
wipe, a netherite upgrade carrying history, armour damage splitting, multi-player
ownership transfer, and behaviour under a large farm. `ACCEPTANCE.md` is the checklist —
steps 6, 9 and 13 first.

**Known limitation at release:** company attribution is a stub. Every craft records as
*Independent* until `CompanyBridge` is wired to the Shop Mod's company API — deliberately,
because the manufacturer line is permanent and a wrong attribution cannot be fixed later.
