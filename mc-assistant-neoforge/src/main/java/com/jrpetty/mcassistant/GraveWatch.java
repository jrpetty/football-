package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Grave guard: the owner dies -> their nearest assistant drops everything,
 * races to the death spot, and secures the drops before they despawn.
 * "give me my stuff" hands it all back after the respawn. Also banks XP for
 * an assistant that lands a kill (spent later at an enchanting table).
 */
public final class GraveWatch {

    private GraveWatch() {}

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        // Fair XP: an assistant that kills a mob earns experience toward enchanting.
        if (event.getSource().getEntity() instanceof AssistantEntity killer
                && !(event.getEntity() instanceof ServerPlayer)) {
            killer.awardXp(xpFor(event.getEntity()));
            killer.note(AssistantEntity.Deed.MOBS_KILLED, 1);
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AssistantEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AssistantEntity a : AssistantEntity.allFor(player.getUUID())) {
            if (a.level() != player.level()) continue;
            double d = a.distanceToSqr(player);
            if (d < 128.0 * 128.0 && d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        if (best == null) return;

        BlockPos pos = player.blockPosition();
        best.clearQueue(); // nothing matters more right now
        best.enqueue(Job.recover(pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        best.say("You're down! Going for your stuff — say \"give me my stuff\" when you're back.");
    }

    /** Hostiles are worth more than animals. */
    private static int xpFor(LivingEntity victim) {
        return victim instanceof Monster ? 6 : 2;
    }
}
