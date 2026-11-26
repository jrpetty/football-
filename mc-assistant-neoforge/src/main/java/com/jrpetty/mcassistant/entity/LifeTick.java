package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] Where the week's life ticks: each folk's part (its hours counted; a service, a christening, a funeral, a
 * dinner, its club's meeting held to: {@link #hold}) and each town's round once a second (the stalls, the fair, the
 * dinner invitations, the clubs, the toasts at a wedding).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class LifeTick {

    private LifeTick() {}

    private static final Map<UUID, Boolean> BUSY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BUSY.clear();
        LifeLog.resetForTests();
        LifeWeights.resetForTests();
        WorkHours.resetForTests();
        WeekendLife.resetForTests();
        Stalls.resetForTests();
        Fairground.resetForTests();
        Friends.resetForTests();
        Church.resetForTests();
        Clubs.resetForTests();
    }

    /** Once a second a folk (VillageFolkEntity.aiStep): its working hours counted, a fifth of an hour every ten seconds. */
    static void tick(VillageFolkEntity f) {
        if (f.tickCount % 200 != 11 || f.isBaby() || f.ownerId() == null || f.level().isNight()) return;
        if (f.onShift() && !f.offWorkNow()) LifeLog.worked(f);
    }

    /** Its part in the week's events now (VillageFolkEntity.aiStep, before sport): true while it is about one. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        boolean b = f.ownerId() != null && !Visitors.is(f) && !f.isSleeping()
            && (Church.hold(level, f) || Friends.hold(level, f) || Clubs.hold(level, f));
        if (b) BUSY.put(f.getUUID(), true);
        else BUSY.remove(f.getUUID());
        return b;
    }

    /** Between its looks: still about it? */
    public static boolean busy(VillageFolkEntity f) {
        return BUSY.containsKey(f.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % 20 != 7) return;
        ServerLevel level = server.overworld();
        for (Villages.Village v : Villages.every()) {
            if (!level.isLoaded(v.centre())) continue;
            round(level, v);
        }
    }

    /** A town's round. */
    public static void round(ServerLevel level, Villages.Village v) {
        try {
            Stalls.round(level, v);
            Fairground.round(level, v);
            Friends.round(level, v);
            Friends.speakToasts(level, v.id());
            Clubs.round(level, v);
        } catch (RuntimeException ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[featlife] the round of {} failed", Villages.name(v.id()), ex);
        }
    }
}
