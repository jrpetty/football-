package com.provenance.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance test 30, plus the identity guarantees of section 2. */
class DuplicationTest {

    private RecordRegistry registry(Path dir) {
        return new RecordRegistry(new FileRecordStore(dir), ProvenanceConfig.withDefaults());
    }

    @Test
    void aValidStampIsAcceptedAndRotated(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon", null, null);

        ItemStamp stamp = ItemStamp.of(sword);
        RecordRegistry.Claim claim = registry.claim(
                stamp, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon");

        assertEquals(RecordRegistry.Status.VALID, claim.status());
        assertSame(sword, claim.record());
        assertEquals(sword.recordId(), claim.stamp().recordId());
        assertNotEquals(stamp.bindingToken(), claim.stamp().bindingToken(),
                "the token must rotate so the old stamp cannot be replayed");
    }

    /**
     * The duplication scenario: one stack is copied, so two stacks carry an
     * identical stamp. Only one may keep the identity.
     */
    @Test
    void aCopiedStackCannotInheritTheOriginalsHistory(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord original = registry.registerCrafted(
                "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon", null, null);
        registry.record(original, player, "Jon", StatKey.BLOCKS_MINED, 50_000);

        // Both stacks now hold the same stamp — this is what a dupe looks like.
        ItemStamp stampA = ItemStamp.of(original);
        ItemStamp stampB = new ItemStamp(stampA.recordId(), stampA.bindingToken());

        RecordRegistry.Claim first = registry.claim(
                stampA, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");
        RecordRegistry.Claim second = registry.claim(
                stampB, "minecraft:diamond_pickaxe", ItemCategory.PICKAXE, player, "Jon");

        assertEquals(RecordRegistry.Status.VALID, first.status());
        assertEquals(RecordRegistry.Status.DUPLICATE, second.status());

        assertEquals(original.recordId(), first.record().recordId(), "the first claimant keeps the identity");
        assertNotEquals(original.recordId(), second.record().recordId(),
                "the copy must never share the original's record id");
        assertEquals(Origin.DUPLICATE, second.record().origin());
        assertEquals(0L, second.record().overall().getOrZero(StatKey.BLOCKS_MINED),
                "the copy starts empty rather than inheriting 50,000 blocks");
        assertEquals(50_000L, original.overall().getOrZero(StatKey.BLOCKS_MINED),
                "the original is untouched");
    }

    @Test
    void replayingAnOldStampIsRefused(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon", null, null);

        ItemStamp stale = ItemStamp.of(sword);
        registry.claim(stale, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon");

        RecordRegistry.Claim replay = registry.claim(
                stale, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon");

        assertEquals(RecordRegistry.Status.DUPLICATE, replay.status());
    }

    @Test
    void anUnknownRecordIdIsNotGrantedAHistory(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);

        RecordRegistry.Claim claim = registry.claim(
                new ItemStamp(UUID.randomUUID(), 12345L),
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, UUID.randomUUID(), "Jon");

        assertEquals(RecordRegistry.Status.UNKNOWN, claim.status());
        assertNull(claim.record());
    }

    @Test
    void peekingDoesNotRotateTheToken(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon", null, null);

        long before = sword.bindingToken();
        ItemRecord peeked = registry.peek(ItemStamp.of(sword));

        assertSame(sword, peeked);
        assertEquals(before, sword.bindingToken(),
                "inspecting an item must not invalidate the stack a player is holding");
    }

    @Test
    void theStoreRefusesTwoRecordsWithTheSameId(@TempDir Path dir) {
        FileRecordStore store = new FileRecordStore(dir);
        UUID id = UUID.randomUUID();

        store.insert(ItemRecord.builder(id, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON)
                .created(0L, false).bindingToken(1L).build());

        assertThrows(IllegalStateException.class, () ->
                store.insert(ItemRecord.builder(id, "minecraft:iron_sword", ItemCategory.MELEE_WEAPON)
                        .created(0L, false).bindingToken(2L).build()));
    }

    @Test
    void aDestroyedIdIsNeverReissued(@TempDir Path dir) {
        RecordRegistry registry = registry(dir);
        UUID player = UUID.randomUUID();
        ItemRecord sword = registry.registerCrafted(
                "minecraft:iron_sword", ItemCategory.MELEE_WEAPON, player, "Jon", null, null);
        UUID destroyedId = sword.recordId();

        registry.markDestroyed(sword);

        assertTrue(sword.isDestroyed());
        assertTrue(registry.store().exists(destroyedId),
                "the id must stay reserved so no future item can inherit this history");
    }
}
