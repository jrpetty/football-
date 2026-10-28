package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [economy] What is left of a raiding party slinks off at dawn, all of it.
 *
 * <p>A raiding band (Raids.band) is summoned to stay (it must not despawn in the middle of its raid), and
 * at dawn the alarm sends off the ones it can find. But one that had wandered out of the loaded ground,
 * or whose alarm was over before it was found (a restart, the bell stopped by day with a straggler in the
 * shade), stayed for good: never despawning, it waited by the town night after night with the next band's
 * raiders and the night's own monsters, and the hundred days' town of fifteen, keeping one guard, went from
 * thirteen monsters about it to thirty-three and lost six folk in a week. So at dawn, every raider of the
 * mod's that is in no raid now going on goes the way the band does.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class RaidStragglers {

    private RaidStragglers() {}

    /** The tag Raids gives its raiders. */
    static final String TAG = "mca_raider";

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 600 != 311) return;
        com.jrpetty.mcassistant.Guard.run("raid stragglers", () -> {
            for (ServerLevel level : server.getAllLevels()) {
                long t = level.getDayTime() % 24000L;
                if (t < 23000L && t >= 1500L) continue;                     // at dawn, as the band itself goes
                sweep(level);
            }
        });
    }

    /** Sends off every raider in no raid now going on; how many went (tests). */
    public static int sweep(ServerLevel level) {
        Set<UUID> raiding = new HashSet<>();
        for (Villages.Village v : Villages.every()) raiding.addAll(Raids.band(v.id()));
        List<Mob> gone = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getTags().contains(TAG) && !raiding.contains(m.getUUID())) gone.add(m);
        }
        for (Mob m : gone) {
            level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.8, m.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
            m.discard();
        }
        return gone.size();
    }
}
