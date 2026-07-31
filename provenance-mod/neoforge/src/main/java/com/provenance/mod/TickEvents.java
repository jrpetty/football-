package com.provenance.mod;

import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * The only periodic work in the system.
 *
 * <p>One sampling pass every five seconds, and a flush every minute. Nothing
 * runs per tick, nothing walks the world's inventories, and items in chests are
 * never sampled, so stored equipment costs nothing.
 *
 * <p>The pass collects two things:
 *
 * <ul>
 *   <li><b>Distance</b> for anything carried or worn.</li>
 *   <li><b>Time worn</b> for armour only. Held tools no longer record time:
 *       "time used" counted a tool merely for sitting in a hand, and collecting
 *       it needed a per-second pass over every player's equipment.</li>
 * </ul>
 *
 * <p>A pass is deliberately cheap: for each of six equipment slots, one data
 * component read and one concurrent map lookup, then a long addition into an
 * in-memory buffer. It never writes to an item stack, never rotates a token and
 * never touches a record, so it cannot dirty an inventory slot or force a
 * client sync. Records are only mutated on the once-a-minute flush.
 *
 * <p>Cost is bounded by online players times six slots, independent of how many
 * items the server has ever tracked. Players who have not moved and wear no
 * armour cost a single early return.
 */
public final class TickEvents {

    /** Slots sampled: held items plus worn armour. */
    private static final EquipmentSlot[] SAMPLED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static int sampleTicks;
    private static int flushTicks;

    private TickEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        if (++sampleTicks >= state.config().distanceIntervalTicks()) {
            int elapsed = sampleTicks;
            sampleTicks = 0;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                sample(state, player, elapsed);
            }
        }

        if (++flushTicks >= state.config().usageFlushIntervalSeconds() * 20) {
            flushTicks = 0;
            state.flushUsage();

            long now = System.currentTimeMillis();
            state.repairs().pruneExpired(now);
            state.transfers().pruneExpired(now);
        }
    }

    private static void sample(ProvenanceState state, ServerPlayer player, int elapsedTicks) {
        if (!Stamps.shouldCount(player)) {
            return;
        }

        long distanceCm = state.distances().sample(
                player.getUUID(),
                Math.round(player.getX() * 100.0d),
                Math.round(player.getY() * 100.0d),
                Math.round(player.getZ() * 100.0d),
                player.level().dimension().location().toString());

        UUID playerId = player.getUUID();

        for (EquipmentSlot slot : SAMPLED_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // Sampling never adopts, renames or rotates. An unregistered item
            // simply is not accruing; it is picked up the moment it is crafted,
            // used, or logged in with.
            ItemRecord record = Stamps.sampled(stack);
            if (record == null) {
                continue;
            }
            if (record.category() == ItemCategory.ARMOUR) {
                state.usage().addTime(record.recordId(), playerId, elapsedTicks);
            }
            if (distanceCm > 0L) {
                state.usage().addDistance(record.recordId(), playerId, distanceCm);
            }
        }
    }
}
