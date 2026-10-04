package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.net.TimeSpeedPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * Fast time: watch a village grow. The whole world runs 2, 4, 8 … 256 times faster, or
 * flat out ("max": as many ticks a second as the machine can manage) — the folk work,
 * crops ripen, days pass and buildings go up at that pace, and you can watch it.
 *
 * <p>It is the game's own tick rate (the one vanilla's {@code /tick rate} sets), so
 * everything speeds up together, the client's view included, and nothing in the village
 * has to know: its clocks are all counted in ticks. What it cannot do is make the server
 * faster than it is — past what the machine can keep up with, the world simply runs as
 * fast as it can, which is why the corner of the screen says both what was asked for and
 * what is actually being managed.
 *
 * <pre>
 *   ]  faster   [  slower   \  back to normal       (keys, rebindable)
 *   /village speed 16 | max | normal | (nothing: say how fast)
 *   the village journal (J): a row of speed buttons
 * </pre>
 *
 * Anyone with operator rights may change it, and so may the player whose single-player
 * world it is, cheats or not.
 */
public final class TimeSpeed {

    private TimeSpeed() {}

    /** "Max": as fast as the machine goes (vanilla's ceiling of 10,000 ticks a second). */
    public static final int MAX = 500;

    /** The steps the keys walk through. */
    public static final int[] STEPS = {1, 2, 4, 8, 16, 32, 64, 128, 256, MAX};

    /** How many times faster than normal the world is set to run. */
    public static int factor(MinecraftServer server) {
        return Math.max(1, Math.round(server.tickRateManager().tickrate() / 20.0F));
    }

    public static String label(int factor) {
        return factor >= MAX ? "max" : factor + "×";
    }

    /** May this player change how fast time runs? */
    public static boolean mayChange(ServerPlayer p) {
        return p.hasPermissions(2) || p.server.isSingleplayerOwner(p.getGameProfile());
    }

    /** Run the world this many times faster (1 = normal), and tell everybody. */
    public static void set(MinecraftServer server, int factor, @Nullable String by) {
        factor = Math.max(1, Math.min(MAX, factor));
        server.tickRateManager().setTickRate(20.0F * factor);
        measuredSince = 0L;
        lastActualX10 = factor * 10;
        String msg = factor == 1 ? "Time runs at its normal pace again."
            : "Time now runs " + (factor >= MAX ? "as fast as this machine can go" : factor + " times faster")
                + (by == null ? "" : " (" + by + ")") + ".";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.displayClientMessage(Component.literal(msg), true);
        }
        broadcast(server);
    }

    /** One step faster (+1) or slower (-1). */
    public static void step(MinecraftServer server, int dir, @Nullable String by) {
        int now = factor(server);
        int next = 1;
        if (dir > 0) {
            next = now;
            for (int s : STEPS) if (s > now) { next = s; break; }
        } else if (dir < 0) {
            // Between two steps (somebody typed /village speed 12): a step slower is the one below.
            for (int s : STEPS) if (s < now) next = s;
        }
        if (next != now) set(server, next, by);
    }

    /** Asked for by a player — the keys, the journal's buttons. mode: 0 set, 1 faster, 2 slower. */
    public static void ask(ServerPlayer p, int mode, int factor) {
        if (!mayChange(p)) {
            p.displayClientMessage(Component.literal("Only an operator (or the owner of this world) can change how fast time runs."), true);
            return;
        }
        String by = p.getName().getString();
        switch (mode) {
            case 1 -> step(p.server, 1, by);
            case 2 -> step(p.server, -1, by);
            default -> set(p.server, factor, by);
        }
    }

    // ------------------------------------------------------------------ how fast it really runs

    private static long measuredSince;
    private static int ticksSince;
    /** Ticks actually run in the last real second, as a speed: 10 = normal, 95 = 9.5 times. */
    private static int lastActualX10 = 10;

    public static int actualX10() { return lastActualX10; }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = System.nanoTime();
        if (measuredSince == 0L) {
            measuredSince = now;
            ticksSince = 0;
            return;
        }
        ticksSince++;
        long spent = now - measuredSince;
        if (spent < 1_000_000_000L) return;
        lastActualX10 = (int) Math.round(ticksSince * 1e10 / spent / 20.0);
        measuredSince = now;
        ticksSince = 0;
        // Once a second while time is fast; at the normal pace nobody needs telling.
        if (factor(server) > 1) broadcast(server);
    }

    /** Whoever joins while time is fast is told so at once. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PacketDistributor.sendToPlayer(p, new TimeSpeedPayload(factor(p.server), lastActualX10));
        }
    }

    private static void broadcast(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(new TimeSpeedPayload(factor(server), lastActualX10));
    }
}
