package com.provenance.mod;

import com.provenance.core.ItemRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;

/**
 * Retires records for items that have ceased to exist.
 *
 * <p>Without this, the store only ever grows: every tool that broke and every
 * sword left to despawn on a cave floor keeps a record forever, and the server
 * carries history for items no player can ever hold again.
 *
 * <p>Two endings, treated differently on purpose:
 *
 * <ul>
 *   <li><b>Despawned.</b> A dropped stack that timed out is unambiguously gone
 *       and nobody is left to ask about it, so the record is deleted outright.</li>
 *   <li><b>Broken.</b> An item worn through by use is marked destroyed and kept
 *       for the configured retention period, so an operator can still answer
 *       "what happened to my relic?", after which the retention sweep removes
 *       it.</li>
 * </ul>
 *
 * <p>Record ids are random, so a retired id can never be handed to another item
 * either way.
 */
public final class ItemLifecycleEvents {

    private ItemLifecycleEvents() {
    }

    /**
     * A dropped item entity reached the end of its life on the ground. Wipe it:
     * the physical item is gone.
     */
    @SubscribeEvent
    public static void onItemExpired(ItemExpireEvent event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        ItemEntity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide()) {
            return;
        }

        ItemStack stack = entity.getItem();
        ItemRecord record = Stamps.peek(stack);
        if (record == null) {
            return;
        }

        // Drop anything still buffered for it first, or the next flush would
        // recreate the file we are about to delete.
        state.usage().forgetRecord(record.recordId());

        if (state.registry().purge(record)) {
            Provenance.LOGGER.debug("Provenance {} wiped: {} despawned",
                    record.recordId(), record.currentItemId());
        }
    }

    /**
     * An item was used until it broke. Keep the record for the retention
     * period, flagged destroyed, then let the sweep remove it.
     */
    @SubscribeEvent
    public static void onItemDestroyed(PlayerDestroyItemEvent event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }

        ItemRecord record = Stamps.peek(event.getOriginal());
        if (record == null) {
            return;
        }

        // Fold in the last few seconds of use before retiring it.
        state.flushUsage(record.recordId());
        state.registry().markDestroyed(record);
        Provenance.LOGGER.debug("Provenance {} retired: {} broke",
                record.recordId(), record.currentItemId());
    }
}
