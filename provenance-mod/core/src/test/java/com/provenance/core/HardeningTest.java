package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the hardening pass: token churn, recoverable forks, retention and usage rules. */
class HardeningTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    // ------------------------------------------------------------------
    // Verify does not rotate
    // ------------------------------------------------------------------

    /**
     * The gameplay path must not rewrite the item every time a block is mined.
     * Rotating there dirties the inventory slot on every action and burns
     * tokens fast enough that ordinary hiccups start looking like duplication.
     */
    @Test
    void verifyDoesNotRotateTheToken(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord pick = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Jon", null, null);

        ItemStamp stamp = ItemStamp.of(pick);
        long before = pick.bindingToken();

        for (int i = 0; i < 1_000; i++) {
            RecordRegistry.Claim claim = registry.verify(stamp);
            assertEquals(RecordRegistry.Status.VALID, claim.status());
            assertSame(pick, claim.record());
        }

        assertEquals(before, pick.bindingToken(), "a thousand recorded actions must not rotate the token once");
    }

    @Test
    void verifyReportsAStaleTokenWithoutActingOnIt(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Jon", null, null);

        ItemStamp stale = ItemStamp.of(sword);
        registry.claim(stale, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Jon");

        RecordRegistry.Claim check = registry.verify(stale);

        assertEquals(RecordRegistry.Status.DUPLICATE, check.status());
        assertSame(sword, check.record(), "verify reports the original rather than forking");
        assertNull(check.stamp(), "verify never issues a new identity");
    }

    // ------------------------------------------------------------------
    // Forks are recoverable
    // ------------------------------------------------------------------

    @Test
    void aForkRecordsWhatItWasForkedFrom(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord original = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon", null, null);
        registry.record(original, player, "Jon", StatKey.BLOCKS_MINED, 120_000);

        ItemStamp copied = ItemStamp.of(original);
        registry.claim(copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");
        RecordRegistry.Claim second = registry.claim(
                copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");

        assertEquals(RecordRegistry.Status.DUPLICATE, second.status());
        assertEquals(original.recordId(), second.record().forkedFromRecordId(),
                "a fork must remember its parent so a false positive can be undone");
        assertEquals(0L, second.record().overall().getOrZero(StatKey.BLOCKS_MINED));
    }

    @Test
    void restoringAFalsePositiveGivesTheParentIdentityBack(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord original = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon", null, null);
        registry.record(original, player, "Jon", StatKey.BLOCKS_MINED, 183_542);

        ItemStamp copied = ItemStamp.of(original);
        registry.claim(copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");
        ItemRecord fork = registry.claim(
                copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon").record();

        ItemStamp restored = registry.restoreFork(fork);

        assertNotNull(restored);
        assertEquals(original.recordId(), restored.recordId(), "the item gets its real history back");

        RecordRegistry.Claim check = registry.verify(restored);
        assertEquals(RecordRegistry.Status.VALID, check.status(), "the restored stamp must be valid");
        assertEquals(183_542, check.record().overall().getOrZero(StatKey.BLOCKS_MINED));

        assertTrue(fork.isDestroyed(), "the fork is retired, not reused");
        assertTrue(registry.store().exists(fork.recordId()), "but its id stays reserved and auditable");
    }

    @Test
    void restoringSomethingThatIsNotAForkDoesNothing(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord ordinary = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Jon", null, null);

        assertNull(registry.restoreFork(ordinary));
        assertNull(registry.restoreFork(null));
        assertFalse(ordinary.isDestroyed());
    }

    @Test
    void forkPointersSurviveRestart(@TempDir Path dir) {
        UUID forkId;
        UUID parentId;
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            UUID player = UUID.randomUUID();
            ItemRecord original = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon", null, null);
            parentId = original.recordId();
            ItemStamp copied = ItemStamp.of(original);
            registry.claim(copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");
            forkId = registry.claim(copied, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon")
                    .record().recordId();
        }

        try (FileRecordStore reopened = new FileRecordStore(dir)) {
            assertEquals(parentId, reopened.load(forkId).forkedFromRecordId());
        }
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    @Test
    void destroyedRecordsArePrunedAndLiveOnesAreNot(@TempDir Path dir) throws Exception {
        FileRecordStore store = new FileRecordStore(dir);
        RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());

        ItemRecord broken = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Jon", null, null);
        ItemRecord alive = registry.registerCrafted(
                "minecraft:netherite_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Jon", null, null);
        registry.markDestroyed(broken);
        store.close();

        // Age both files well past the retention window.
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - (200L * 86_400_000L));
        Files.setLastModifiedTime(pathOf(dir, broken.recordId()), old);
        Files.setLastModifiedTime(pathOf(dir, alive.recordId()), old);

        try (FileRecordStore reopened = new FileRecordStore(dir)) {
            int removed = reopened.pruneDestroyed(90);

            assertEquals(1, removed);
            assertNull(reopened.load(broken.recordId()), "a destroyed record past retention is removed");
            assertNotNull(reopened.load(alive.recordId()), "a live item is never pruned, however old");
        }
    }

    @Test
    void recentlyDestroyedRecordsSurviveTheSweep(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            ItemRecord broken = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Jon", null, null);
            registry.markDestroyed(broken);
            store.flush();

            assertEquals(0, store.pruneDestroyed(90));
            assertNotNull(store.load(broken.recordId()));
        }
    }

    @Test
    void negativeRetentionKeepsEverythingForever(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            ItemRecord broken = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, UUID.randomUUID(), "Jon", null, null);
            registry.markDestroyed(broken);
            store.flush();

            assertEquals(0, store.pruneDestroyed(-1));
        }
    }

    private static Path pathOf(Path root, UUID id) {
        return root.resolve(id.toString().substring(0, 2)).resolve(id + ".json");
    }

    // ------------------------------------------------------------------
    // Usage accounting
    // ------------------------------------------------------------------

    /**
     * Time is counted every second an item is held, with no activity gate. An
     * hour mining one spot is an hour the pickaxe was in service, and the
     * earlier movement-gated version recorded none of it.
     */
    @Test
    void timeAccruesEverySecondWithoutMovement() {
        UsageAccumulator usage = new UsageAccumulator();
        UUID pick = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        // One hour standing still, sampled once a second.
        for (int second = 0; second < 3_600; second++) {
            usage.addTime(pick, jon, 20);
        }

        long[] drained = new long[2];
        usage.drain((recordId, playerId, ticks, distanceCm) -> {
            drained[0] += ticks;
            drained[1] += distanceCm;
        });

        assertEquals(72_000L, drained[0], "an hour held is an hour recorded");
        assertEquals(0L, drained[1], "standing still accrues no distance");
    }

    /**
     * Counting every second must not mean writing every second, or records
     * would never go clean and the store would rewrite them forever.
     */
    @Test
    void aSecondOfSamplingCostsOneBufferEntryNotOneWrite() {
        UsageAccumulator usage = new UsageAccumulator();
        UUID pick = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        for (int i = 0; i < 10_000; i++) {
            usage.addTime(pick, jon, 20);
            usage.addDistance(pick, jon, 5);
        }

        assertEquals(1, usage.pendingEntries(), "ten thousand samples collapse into one buffered entry");

        long[] totals = new long[2];
        usage.drain((r, p, ticks, cm) -> {
            totals[0] = ticks;
            totals[1] = cm;
        });
        assertEquals(200_000L, totals[0]);
        assertEquals(50_000L, totals[1]);
        assertEquals(0, usage.pendingEntries(), "draining clears the buffer");
    }

    @Test
    void eachPlayerAccruesSeparatelyOnTheSameItem() {
        UsageAccumulator usage = new UsageAccumulator();
        UUID pick = UUID.randomUUID();
        UUID jon = UUID.randomUUID();
        UUID steve = UUID.randomUUID();

        usage.addTime(pick, jon, 200);
        usage.addTime(pick, steve, 60);

        assertEquals(2, usage.pendingEntries());

        Map<UUID, Long> byPlayer = new java.util.HashMap<>();
        usage.drain((r, p, ticks, cm) -> byPlayer.merge(p, ticks, Long::sum));

        assertEquals(200L, byPlayer.get(jon));
        assertEquals(60L, byPlayer.get(steve));
    }

    /** Inspecting an item must show totals that include the moment you look. */
    @Test
    void drainingOneRecordLeavesOthersBuffered() {
        UsageAccumulator usage = new UsageAccumulator();
        UUID inspected = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        usage.addTime(inspected, jon, 100);
        usage.addTime(other, jon, 100);

        long[] seen = new long[1];
        usage.drainRecord(inspected, (r, p, ticks, cm) -> seen[0] += ticks);

        assertEquals(100L, seen[0]);
        assertEquals(1, usage.pendingEntries(), "the other item's buffer is untouched");
    }

    @Test
    void buffersForAVanishedItemAreDiscarded() {
        UsageAccumulator usage = new UsageAccumulator();
        UUID gone = UUID.randomUUID();
        UUID jon = UUID.randomUUID();

        usage.addTime(gone, jon, 500);
        usage.forgetRecord(gone);

        assertEquals(0, usage.pendingEntries());
        usage.drain((r, p, ticks, cm) -> fail("nothing should be drained for a purged item"));
    }

    @Test
    void armourAndToolsRecordAgainstDifferentCounters() {
        assertEquals(StatKey.TIME_WORN_TICKS, UsageAccumulator.timeKeyFor(ItemCategory.ARMOUR));
        assertNull(UsageAccumulator.timeKeyFor(ItemCategory.PICKAXE),
                "only armour records time");
    }

    // ------------------------------------------------------------------
    // Despawned items are wiped
    // ------------------------------------------------------------------

    @Test
    void purgingRemovesAVanishedItemCompletely(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            UUID jon = UUID.randomUUID();
            ItemRecord dropped = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, jon, "Jon", null, null);
            registry.record(dropped, jon, "Jon", StatKey.BLOCKS_MINED, 5_000);
            UUID id = dropped.recordId();
            store.flush();

            assertTrue(store.exists(id));
            assertTrue(registry.purge(dropped), "a despawned item's record is deleted");

            assertNull(store.load(id));
            assertFalse(store.exists(id), "nothing is left on disk for an item that no longer exists");
        }
    }

    @Test
    void purgingDiscardsUnwrittenChangesRatherThanResurrectingTheFile(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            UUID jon = UUID.randomUUID();
            ItemRecord dropped = registry.registerCrafted(
                    "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, jon, "Jon", null, null);
            UUID id = dropped.recordId();

            // Never flushed, so the record is dirty when it is purged.
            registry.record(dropped, jon, "Jon", StatKey.KILLS, 3);
            registry.purge(dropped);
            store.flush();

            assertFalse(store.exists(id), "a later flush must not recreate a purged record");
        }
    }

    @Test
    void purgingSomethingThatIsNotThereIsHarmless(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            assertFalse(registry.purge(null));
            assertFalse(store.delete(UUID.randomUUID()));
        }
    }

    // ------------------------------------------------------------------
    // Record volume
    // ------------------------------------------------------------------

    /**
     * Every material tier earns a history, armour included. A stone pickaxe
     * that mines a hundred thousand blocks is as much an antique as a netherite
     * one, and a starter leather chestplate kept for a year is the whole point.
     */
    @Test
    void everyMaterialTierIsTrackedIncludingArmour() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        assertEquals(ItemCategory.PICKAXE,
                config.resolveCategory("minecraft:stone_pickaxe", java.util.List.of("minecraft:pickaxes")));
        assertEquals(ItemCategory.MELEE_WEAPON,
                config.resolveCategory("minecraft:golden_sword", java.util.List.of("minecraft:swords")));
        assertEquals(ItemCategory.SHOVEL,
                config.resolveCategory("minecraft:stone_shovel", java.util.List.of("minecraft:shovels")));

        // Armour, across every material.
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:leather_boots", java.util.List.of("minecraft:foot_armor")));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:leather_chestplate", java.util.List.of("minecraft:chest_armor")));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:golden_helmet", java.util.List.of("minecraft:head_armor")));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:chainmail_leggings", java.util.List.of("minecraft:leg_armor")));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:iron_chestplate", java.util.List.of("minecraft:chest_armor")));
        assertEquals(ItemCategory.ARMOUR,
                config.resolveCategory("minecraft:netherite_chestplate", java.util.List.of("minecraft:chest_armor")));

        assertNotEquals(null, config.resolveCategory("minecraft:iron_pickaxe", java.util.List.of("minecraft:pickaxes")));
        assertNotEquals(null, config.resolveCategory("minecraft:diamond_sword", java.util.List.of("minecraft:swords")));
    }

    /** Nothing is excluded: a wooden pickaxe kept for a year is an antique too. */
    @Test
    void nothingIsExcludedByDefaultIncludingWood() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        assertEquals(ItemCategory.PICKAXE,
                config.resolveCategory("minecraft:wooden_pickaxe", java.util.List.of("minecraft:pickaxes")));
        assertEquals(ItemCategory.MELEE_WEAPON,
                config.resolveCategory("minecraft:wooden_sword", java.util.List.of("minecraft:swords")));
        assertTrue(config.excludedItems().isEmpty(), "no item is excluded out of the box");
    }

    /**
     * Firearms from Timeless and Classics Zero. Every gun in that mod shares
     * one registry entry, so eligibility keys off that entry while the record's
     * displayed identity comes from the individual stack.
     */
    @Test
    void firearmsAreTrackedAsTheirOwnCategory() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();

        assertEquals(ItemCategory.GUN,
                config.resolveCategory("tacz:modern_kinetic_gun", java.util.List.of()));

        MilestoneTrack track = config.track(ItemCategory.GUN);
        assertEquals(StatKey.KILLS, track.primaryStat(), "a gun's ladder is measured in kills");
        assertEquals(10L, track.threshold(MilestoneTier.INITIATED));
        assertEquals(100_000L, track.threshold(MilestoneTier.SERVER_RELIC));
    }

    /**
     * Accuracy is only meaningful because a gun mod reports every shot. The
     * counters must therefore stay independent: hits cannot exceed shots by
     * accident, and both aggregate as plain sums across contributors.
     */
    @Test
    void gunsRecordShotsHitsAndHeadshotsSeparately(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID gunner = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ItemRecord rifle = registry.registerCrafted(
                "tacz:ak47", ItemCategory.GUN, gunner, "Gunner", null, null);

        registry.record(rifle, gunner, "Gunner", StatKey.SHOTS_FIRED, 120);
        registry.record(rifle, gunner, "Gunner", StatKey.PROJECTILE_HITS, 74);
        registry.record(rifle, gunner, "Gunner", StatKey.HEADSHOTS, 11);
        registry.record(rifle, second, "Second", StatKey.SHOTS_FIRED, 30);
        registry.record(rifle, second, "Second", StatKey.PROJECTILE_HITS, 9);

        assertEquals(150, rifle.overall().getOrZero(StatKey.SHOTS_FIRED));
        assertEquals(83, rifle.overall().getOrZero(StatKey.PROJECTILE_HITS));
        assertEquals(11, rifle.overall().getOrZero(StatKey.HEADSHOTS));
        assertEquals(74, rifle.contribution(gunner).stats().getOrZero(StatKey.PROJECTILE_HITS));
        assertTrue(rifle.verifyOverallMatchesContributors());
    }

    @Test
    void aGunEarnsTiersOnKillsLikeAnyOtherWeapon(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID gunner = UUID.randomUUID();
        ItemRecord rifle = registry.registerCrafted(
                "tacz:ak47", ItemCategory.GUN, gunner, "Gunner", null, null);

        registry.record(rifle, gunner, "Gunner", StatKey.KILLS, 250);

        assertEquals(MilestoneTier.SEASONED, rifle.currentTier());
        // Firing a lot without killing anything must not advance the ladder.
        assertTrue(registry.record(rifle, gunner, "Gunner", StatKey.SHOTS_FIRED, 50_000).isEmpty());
        assertEquals(MilestoneTier.SEASONED, rifle.currentTier());
    }

    @Test
    void aServerCanStillExcludeWhateverItWants() {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        config.exclude("minecraft:wooden_pickaxe");

        assertNull(config.resolveCategory("minecraft:wooden_pickaxe", java.util.List.of("minecraft:pickaxes")));
    }
}
