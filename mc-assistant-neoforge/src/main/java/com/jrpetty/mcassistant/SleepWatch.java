package com.jrpetty.mcassistant;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Sleeping ends the night, wherever anybody happens to be.
 *
 * <p>A world with settlements in it is a world where a lot is going on a long
 * way from the player, and the last thing anyone wants is to go to bed and
 * still be lying there at dawn. When everybody who is playing is asleep, the
 * morning comes — no proximity rule, no waiting on anything that is happening
 * in a village four hundred blocks away.
 *
 * <p>Deliberately conservative about the things a player may have deliberately
 * set: if the daylight cycle is switched off, this does nothing at all.
 */
public final class SleepWatch {

    private SleepWatch() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Once a second is plenty; sleeping is not a per-tick decision.
        if (event.getServer().getTickCount() % 20 != 0) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!level.dimensionType().natural()) continue;          // no sleeping the Nether away
            if (!level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) continue;

            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;

            boolean anyAsleep = false;
            boolean anyAwake = false;
            for (ServerPlayer p : players) {
                if (p.isSpectator()) continue;                        // watchers do not vote
                if (p.isSleeping()) anyAsleep = true; else anyAwake = true;
            }
            if (!anyAsleep || anyAwake) continue;
            if (!level.isNight() && !level.isThundering()) continue;

            // Straight to the next dawn, and the storm with it — the same two
            // things vanilla does, done unconditionally so nothing happening
            // elsewhere in the world can hold the night open.
            long day = level.getDayTime() / 24000L;
            level.setDayTime((day + 1) * 24000L);
            if (level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
                level.resetWeatherCycle();
            }
            for (ServerPlayer p : players) {
                if (p.isSleeping()) p.stopSleepInBed(false, false);
            }
        }
    }
}
