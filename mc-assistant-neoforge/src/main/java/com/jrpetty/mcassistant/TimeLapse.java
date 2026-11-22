package com.jrpetty.mcassistant;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;

/**
 * [vision27] A time-lapse for the camera (client/CameraScreen's "Time-lapse" button): the world runs so many times faster
 * (TimeSpeed, the game's own tick rate) for a short while of real time, then goes back to the pace it had before, by
 * itself. Long enough to watch a house go up wall by wall; short enough that nobody is left in a world running away
 * with itself. Only for whoever may change how fast time runs (an operator, or the owner of a single-player world):
 * the same rule as the speed keys.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class TimeLapse {

    private TimeLapse() {}

    /** The fastest a time-lapse may ask for, and the longest it may last (real seconds). */
    public static final int MOST_FACTOR = 32, MOST_SECONDS = 60;

    /** The pace to go back to, and when (System.nanoTime); 0 when no time-lapse is running. */
    private static int before = 1;
    private static long until;
    @Nullable private static String by;

    public static boolean running() {
        return until != 0L;
    }

    /** Real seconds left, or 0. */
    public static int secondsLeft() {
        return until == 0L ? 0 : (int) Math.max(0L, (until - System.nanoTime()) / 1_000_000_000L);
    }

    /**
     * Start one, asked for by this player: {@code factor} times faster for {@code seconds} of real time. Returns what to
     * tell the player; {@code null} when it started.
     */
    @Nullable
    public static String start(ServerPlayer p, int factor, int seconds) {
        if (!TimeSpeed.mayChange(p)) return "Only an operator (or the owner of this world) can run a time-lapse.";
        factor = Math.max(2, Math.min(MOST_FACTOR, factor));
        seconds = Math.max(3, Math.min(MOST_SECONDS, seconds));
        if (!running()) before = TimeSpeed.factor(p.server);
        by = p.getName().getString();
        until = System.nanoTime() + seconds * 1_000_000_000L;
        TimeSpeed.set(p.server, factor, by + "'s time-lapse");
        com.jrpetty.mcassistant.entity.LapseBuild.begin(p, seconds);       // [life28] the building nearest goes up whole in it
        return null;
    }

    /** Stop it now, the pace put back as it was. */
    public static void stop(MinecraftServer server) {
        if (!running()) return;
        until = 0L;
        com.jrpetty.mcassistant.entity.LapseBuild.end();                    // [life28]
        TimeSpeed.set(server, Math.max(1, before), by == null ? null : by + "'s time-lapse is over");
        before = 1;
        by = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (until != 0L && System.nanoTime() - until >= 0L) stop(event.getServer());
        if (until != 0L) com.jrpetty.mcassistant.entity.LapseBuild.tick(event.getServer());   // [life28]
    }

    /** A server stopped mid time-lapse comes back at its old pace (the tick rate is not saved, but be sure). */
    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        stop(event.getServer());
    }

    /** For the tests: the pace it will go back to. */
    public static int beforeForTests() {
        return before;
    }
}
