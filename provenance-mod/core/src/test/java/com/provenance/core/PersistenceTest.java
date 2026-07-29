package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 4, 24 and 29. */
class PersistenceTest {

    /** Everything survives a full store close and reopen, i.e. a server restart. */
    @Test
    void aCompleteRecordSurvivesRestart(@TempDir Path dir) {
        UUID james = UUID.randomUUID();
        UUID jon = UUID.randomUUID();
        UUID recordId;
        long created;

        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            ItemRecord pick = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, james, "James", "deepcore", "Deepcore Mining Ltd");
            recordId = pick.recordId();
            created = pick.createdEpochMilli();

            registry.record(pick, james, "James", StatKey.BLOCKS_MINED, 131_395);
            registry.record(pick, jon, "Jon", StatKey.BLOCKS_MINED, 43_207);
            registry.record(pick, jon, "Jon", StatKey.REPAIRS, 2);
            registry.record(pick, jon, "Jon", StatKey.DEEPEST_BLOCK_Y, -58);
            registry.recordBreakdown(pick, james, "James", BreakdownKind.BLOCK_MINED, "minecraft:stone", 120_000);
            pick.setOwner(jon, "Jon");
            pick.setCustomName("Steelbreaker");
            registry.applyUpgrade(pick, "minecraft:netherite_pickaxe");
        }

        // Fresh store object, cold cache — this is what a restart looks like.
        try (FileRecordStore reopened = new FileRecordStore(dir)) {
            ItemRecord pick = reopened.load(recordId);
            assertNotNull(pick, "the record must be found after restart");

            assertEquals(created, pick.createdEpochMilli(), "creation date survives restart");
            assertEquals("James", pick.crafterDisplayName());
            assertEquals(james, pick.crafterId());
            assertEquals("Deepcore Mining Ltd", pick.manufacturerDisplayName());
            assertEquals("deepcore", pick.companyId());
            assertEquals(Origin.CRAFTED, pick.origin());
            assertEquals("minecraft:diamond_pickaxe", pick.originalItemId());
            assertEquals("minecraft:netherite_pickaxe", pick.currentItemId());
            assertEquals("Steelbreaker", pick.customName());
            assertEquals(jon, pick.currentOwnerId());
            assertEquals("Jon", pick.currentOwnerDisplayName());

            assertEquals(174_602, pick.overall().getOrZero(StatKey.BLOCKS_MINED));
            assertEquals(2, pick.contributorCount());
            assertEquals(131_395, pick.contribution(james).stats().getOrZero(StatKey.BLOCKS_MINED));
            assertEquals(43_207, pick.contribution(jon).stats().getOrZero(StatKey.BLOCKS_MINED));
            assertEquals(-58, pick.overall().getOrZero(StatKey.DEEPEST_BLOCK_Y));
            assertEquals(120_000, pick.overall().breakdown(BreakdownKind.BLOCK_MINED).get("minecraft:stone"));

            assertEquals(MilestoneTier.VETERAN, pick.currentTier(), "milestones survive restart");
            assertNotNull(pick.award(MilestoneTier.VETERAN));
            assertTrue(pick.verifyOverallMatchesContributors());
        }
    }

    @Test
    void milestoneDatesSurviveRestart(@TempDir Path dir) {
        UUID player = UUID.randomUUID();
        UUID recordId;
        long awardedAt;

        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults(), () -> 1_234_567L);
            ItemRecord sword = registry.registerCrafted(
                    "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);
            recordId = sword.recordId();
            registry.record(sword, player, "A", StatKey.KILLS, 10);
            awardedAt = sword.award(MilestoneTier.INITIATED).achievedEpochMilli();
        }

        try (FileRecordStore reopened = new FileRecordStore(dir)) {
            ItemRecord sword = reopened.load(recordId);
            assertEquals(awardedAt, sword.award(MilestoneTier.INITIATED).achievedEpochMilli());
            assertEquals(StatKey.KILLS, sword.award(MilestoneTier.INITIATED).triggeringStat());
            assertEquals(10L, sword.award(MilestoneTier.INITIATED).triggeringValue());
        }
    }

    @Test
    void theBindingTokenSurvivesRestartSoHeldItemsStayValid(@TempDir Path dir) {
        UUID player = UUID.randomUUID();
        UUID recordId;
        long token;

        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            ItemRecord sword = registry.registerCrafted(
                    "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);
            recordId = sword.recordId();
            token = sword.bindingToken();
        }

        try (FileRecordStore reopened = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(reopened, ProvenanceConfig.withDefaults());
            RecordRegistry.Claim claim = registry.claim(new ItemStamp(recordId, token),
                    "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A");
            assertEquals(RecordRegistry.Status.VALID, claim.status(),
                    "a player holding an item across a restart must not have it treated as a dupe");
        }
    }

    @Test
    void anUnknownIdLoadsAsNullRatherThanBeingInvented(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            assertNull(store.load(UUID.randomUUID()));
            assertFalse(store.exists(UUID.randomUUID()));
        }
    }

    @Test
    void recordsAreShardedAcrossDirectories(@TempDir Path dir) throws Exception {
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            for (int i = 0; i < 200; i++) {
                registry.registerCrafted("minecraft:iron_sword", ItemCategory.MELEE_WEAPON,
                        UUID.randomUUID(), "A", null, null);
            }
            store.flush();
        }

        long shardDirs;
        try (var stream = Files.list(dir)) {
            shardDirs = stream.filter(Files::isDirectory).count();
        }
        assertTrue(shardDirs > 1, "records must not all land in one directory");
    }

    /**
     * Dirty records must never be dropped by cache pressure, or statistics
     * would be lost before reaching disk.
     */
    @Test
    void cachePressureNeverEvictsUnsavedWork(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir, 16)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            UUID player = UUID.randomUUID();

            ItemRecord first = registry.registerCrafted(
                    "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "A", null, null);
            registry.record(first, player, "A", StatKey.KILLS, 42);
            UUID firstId = first.recordId();

            for (int i = 0; i < 400; i++) {
                registry.registerCrafted("minecraft:iron_sword", ItemCategory.MELEE_WEAPON,
                        UUID.randomUUID(), "B", null, null);
            }

            store.flush();
            ItemRecord reloaded = store.load(firstId);
            assertNotNull(reloaded);
            assertEquals(42, reloaded.overall().getOrZero(StatKey.KILLS));
        }
    }

    @Test
    void theCacheStaysBoundedOnceRecordsAreClean(@TempDir Path dir) {
        try (FileRecordStore store = new FileRecordStore(dir, 32)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults());
            for (int i = 0; i < 500; i++) {
                registry.registerCrafted("minecraft:iron_sword", ItemCategory.MELEE_WEAPON,
                        UUID.randomUUID(), "A", null, null);
            }
            store.flush();
            assertTrue(store.cachedCount() <= 32,
                    "a long-lived server must not hold every record in memory, saw " + store.cachedCount());
        }
    }

    /** Acceptance test 24. */
    @Test
    void legacyEquipmentMigratesWithoutFabricatingHistory(@TempDir Path dir) {
        long migrationTime = 1_700_000_000_000L;
        try (FileRecordStore store = new FileRecordStore(dir)) {
            RecordRegistry registry = new RecordRegistry(store, ProvenanceConfig.withDefaults(), () -> migrationTime);
            UUID holder = UUID.randomUUID();

            ItemRecord legacy = registry.registerLegacy(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, holder, "Steve");

            assertEquals(Origin.LEGACY, legacy.origin());
            assertTrue(legacy.creationDateApproximate());
            assertEquals(migrationTime, legacy.createdEpochMilli());
            assertEquals("Unknown", legacy.crafterDisplayName());
            assertEquals("Unknown", legacy.manufacturerDisplayName());
            assertEquals(holder, legacy.currentOwnerId());
            assertEquals(0, legacy.contributorCount());
            assertNull(legacy.currentTier());
            assertEquals(0L, legacy.overall().getOrZero(StatKey.BLOCKS_MINED));
        }
    }
}
