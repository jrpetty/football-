package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance tests 1, 2, 3 and 20. */
class CreationRecordTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    @Test
    void newlyCraftedItemReceivesOneUniqueRecordId(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID crafter = UUID.randomUUID();

        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ItemRecord record = registry.registerCrafted(
                    "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, crafter, "Jon", null, null);
            assertTrue(ids.add(record.recordId()), "record id was issued twice");
        }
        assertEquals(500, ids.size());
    }

    @Test
    void originalCrafterAndCompanyAreStored(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID crafter = UUID.randomUUID();

        ItemRecord record = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE,
                crafter, "James", "deepcore", "Deepcore Mining Ltd");

        assertEquals(crafter, record.crafterId());
        assertEquals("James", record.crafterDisplayName());
        assertEquals("deepcore", record.companyId());
        assertEquals("Deepcore Mining Ltd", record.manufacturerDisplayName());
        assertEquals(Origin.CRAFTED, record.origin());
    }

    @Test
    void independentCraftingDoesNotAssignACompany(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);

        ItemRecord record = registry.registerCrafted(
                "minecraft:netherite_chestplate", ItemCategory.ARMOUR,
                UUID.randomUUID(), "Jon", null, null);

        assertNull(record.companyId());
        assertEquals("Independent", record.manufacturerDisplayName());
    }

    @Test
    void foundItemsInventNoMaker(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);

        ItemRecord record = registry.registerFound(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Steve");

        assertEquals("Unknown", record.crafterDisplayName());
        assertEquals("Unknown", record.manufacturerDisplayName());
        assertNull(record.crafterId());
        assertEquals(Origin.FOUND, record.origin());
    }

    @Test
    void legacyItemsAreFlaggedRatherThanBackdated(@TempDir Path dir) {
        long migrationTime = 1_700_000_000_000L;
        RecordRegistry registry = new RecordRegistry(
                new FileRecordStore(dir), ProvenanceConfig.withDefaults(), () -> migrationTime);

        ItemRecord record = registry.registerLegacy(
                "minecraft:diamond_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Steve");

        assertEquals(Origin.LEGACY, record.origin());
        assertEquals(migrationTime, record.createdEpochMilli(), "creation date must be the migration date");
        assertTrue(record.creationDateApproximate(), "legacy dates must be flagged, never presented as exact");
        assertEquals(0L, record.overall().getOrZero(StatKey.KILLS), "counters start at zero");
    }

    /** Acceptance test 20: renaming is cosmetic and cannot touch provenance. */
    @Test
    void renamingDoesNotAlterAuthenticatedProvenance(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID crafter = UUID.randomUUID();
        ItemRecord record = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, crafter, "James", "deepcore", "Deepcore Mining Ltd");

        record.setCustomName("Server Relic Ultimate Legendary");

        assertEquals("Server Relic Ultimate Legendary", record.customName());
        assertEquals("James", record.crafterDisplayName(), "crafter is immutable");
        assertEquals("Deepcore Mining Ltd", record.manufacturerDisplayName(), "manufacturer is immutable");
        assertNull(record.currentTier(), "a name cannot forge a milestone tier");
    }

    @Test
    void upgradePreservesOriginalTypeAndIdentity(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        ItemRecord record = registry.registerCrafted(
                "minecraft:diamond_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Sarah", null, null);
        UUID originalId = record.recordId();

        registry.applyUpgrade(record, "minecraft:netherite_sword");

        assertEquals(originalId, record.recordId());
        assertEquals("minecraft:diamond_sword", record.originalItemId());
        assertEquals("minecraft:netherite_sword", record.currentItemId());
        assertNotEquals(record.originalItemId(), record.currentItemId());
    }
}
