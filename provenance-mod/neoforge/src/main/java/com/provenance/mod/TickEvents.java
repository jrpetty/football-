package com.provenance.mod;

import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import com.provenance.core.StatKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The only periodic work in the system.
 *
 * <p>Distance and time-carried cannot come from events, so they are sampled —
 * but on a configurable interval (five seconds by default), not per tick, and
 * only for online players. Nothing walks the world's inventories, and an item
 * sitting in a chest is never sampled at all, so static storage accrues
 * nothing.
 *
 * <p>The per-pass cost is bounded by the number of online players times the
 * number of equipment slots, independent of how many items the server has ever
 * tracked.
 */
public final class TickEvents {

    /** Slots sampled for carry distance and time: held items plus worn armour. */
    private static final EquipmentSlot[] SAMPLED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static int tickCounter;

    private TickEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        int interval = state.config().distanceIntervalTicks();
        if (++tickCounter < interval) {
            return;
        }
        int elapsedTicks = tickCounter;
        tickCounter = 0;

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            samplePlayer(state, player, elapsedTicks);
        }

        // Housekeeping, kept off the hot path.
        long now = System.currentTimeMillis();
        state.repairs().pruneExpired(now);
        state.transfers().pruneExpired(now);
    }

    private static void samplePlayer(ProvenanceState state, ServerPlayer player, int elapsedTicks) {
        if (!Stamps.shouldCount(player)) {
            return;
        }

        long distanceCm = state.distances().sample(
                player.getUUID(),
                Math.round(player.getX() * 100.0d),
                Math.round(player.getY() * 100.0d),
                Math.round(player.getZ() * 100.0d),
                player.level().dimension().location().toString());

        // An idle player accrues neither distance nor "time used": standing in
        // a corner holding a pickaxe is not using it.
        boolean moved = distanceCm > 0;

        List<ItemRecord> carried = new ArrayList<>(SAMPLED_SLOTS.length);
        List<ItemCategory> categories = new ArrayList<>(SAMPLED_SLOTS.length);

        for (EquipmentSlot slot : SAMPLED_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemRecord record = Stamps.resolve(stack, player);
            if (record == null) {
                continue;
            }
            carried.add(record);
            categories.add(record.category());
        }

        if (carried.isEmpty()) {
            return;
        }

        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();

        for (int i = 0; i < carried.size(); i++) {
            ItemRecord record = carried.get(i);
            if (moved) {
                state.registry().record(record, id, name, StatKey.DISTANCE_CM, distanceCm);
            }
            StatKey timeKey = categories.get(i) == ItemCategory.ARMOUR
                    ? StatKey.TIME_WORN_TICKS
                    : StatKey.TIME_USED_TICKS;
            // Armour is worn whether or not the wearer moves; a held tool only
            // accrues "time used" while its holder is actually doing something.
            if (categories.get(i) == ItemCategory.ARMOUR || moved) {
                state.registry().record(record, id, name, timeKey, elapsedTicks);
            }
        }
    }
}
