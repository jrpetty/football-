package com.provenance.mod;

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
 * <p>Three independent cadences, so each job runs as often as it needs and no
 * more:
 *
 * <ul>
 *   <li><b>Time</b>, every second. An item accrues every second it is held or
 *       worn, with no activity gate, so an hour spent mining one spot counts as
 *       an hour of use.</li>
 *   <li><b>Distance</b>, every five seconds. Position deltas do not need
 *       per-second resolution, and sampling less often costs nothing in
 *       accuracy.</li>
 *   <li><b>Flush</b>, every minute. Buffered totals are written into records
 *       and housekeeping runs.</li>
 * </ul>
 *
 * <p>The per-second pass is deliberately cheap: a component read and a cached
 * map lookup per equipped slot, then two long additions into an in-memory
 * buffer. It never writes to an item stack, never rotates a token, and never
 * touches a record, so it cannot dirty an inventory slot or force a client
 * sync. Records are only mutated on the once-a-minute flush.
 *
 * <p>Cost is bounded by online players times six equipment slots, independent
 * of how many items the server has ever tracked. Items in chests are never
 * sampled, so stored equipment accrues nothing.
 */
public final class TickEvents {

    /** Slots sampled for time and distance: held items plus worn armour. */
    private static final EquipmentSlot[] SAMPLED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static int timeTicks;
    private static int distanceTicks;
    private static int flushTicks;

    private TickEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        int timeInterval = state.config().timeSampleIntervalTicks();
        int distanceInterval = state.config().distanceIntervalTicks();
        int flushInterval = state.config().usageFlushIntervalSeconds() * 20;

        boolean sampleTime = ++timeTicks >= timeInterval;
        boolean sampleDistance = ++distanceTicks >= distanceInterval;

        if (sampleTime || sampleDistance) {
            int elapsed = timeTicks;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                sample(state, player, sampleTime, elapsed, sampleDistance);
            }
        }

        if (sampleTime) {
            timeTicks = 0;
        }
        if (sampleDistance) {
            distanceTicks = 0;
        }

        if (++flushTicks >= flushInterval) {
            flushTicks = 0;
            state.flushUsage();

            long now = System.currentTimeMillis();
            state.repairs().pruneExpired(now);
            state.transfers().pruneExpired(now);
        }
    }

    private static void sample(ProvenanceState state, ServerPlayer player,
                               boolean sampleTime, int elapsedTicks, boolean sampleDistance) {
        if (!Stamps.shouldCount(player)) {
            return;
        }

        long distanceCm = 0L;
        if (sampleDistance) {
            distanceCm = state.distances().sample(
                    player.getUUID(),
                    Math.round(player.getX() * 100.0d),
                    Math.round(player.getY() * 100.0d),
                    Math.round(player.getZ() * 100.0d),
                    player.level().dimension().location().toString());
        }

        if (!sampleTime && distanceCm <= 0L) {
            return;
        }

        UUID playerId = player.getUUID();

        for (EquipmentSlot slot : SAMPLED_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // Sampling never adopts, renames or rotates. An item that has not
            // been registered yet simply is not accruing time; it will be
            // picked up the moment it is crafted, used, or logged in with.
            ItemRecord record = Stamps.sampled(stack);
            if (record == null) {
                continue;
            }
            if (sampleTime) {
                state.usage().addTime(record.recordId(), playerId, elapsedTicks);
            }
            if (distanceCm > 0L) {
                state.usage().addDistance(record.recordId(), playerId, distanceCm);
            }
        }
    }
}
