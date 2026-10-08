package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.Guard;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * [war-scouting] The scouting side of a war, ticked: every second each town's watch looks out for spies
 * (Spies) and keeps its pickets (Pickets); every five seconds, of a morning, a town on a war footing sends
 * somebody to look at its rivals (Spying). Off with the villageWars switch, none of it happens (a captive
 * still held is still held, and let go at peace).
 */
public final class WarScouting {

    private WarScouting() {}

    /** Tests: the towns' own look round held off, so that a test drives each part itself. */
    private static volatile boolean paused;

    public static void pauseForTests(boolean on) {
        paused = on;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int t = event.getServer().getTickCount();
        if (t % 20 != 9 || paused) return;
        boolean wars = AssistantConfig.villageWars();
        Guard.run("war-scouting", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    Spies.tick(level, v);
                    if (!wars) continue;
                    Pickets.tick(level, v);
                    if (t % 100 == 9) Spying.tick(level, v);
                }
            }
        });
    }

    /**
     * The folk's own step (VillageFolkEntity.aiStep, every tick; {@code think} one tick in five): held captive
     * at an enemy's barracks, after a spy, or on picket on the road. True while that is its day just now.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level, boolean think) {
        return Spies.hold(f, level, think) || Spies.chase(f, level, think) || Pickets.stand(f, level, think);
    }

    public static void resetForTests() {
        paused = false;
        Spying.resetForTests();
        Spies.resetForTests();
        Pickets.resetForTests();
        Intel.resetForTests();
    }
}
