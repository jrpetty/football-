package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [pa33g] It keeps the ground round it awake when it has reason to be somewhere its player isn't: told to stay, at a
 * guard post, at work, or walking over to them (a come-here). A small square of chunks (3x3 by default) is held with a
 * forced, ticking ticket, so it truly stays and keeps working while its player is hundreds of blocks off. Only while
 * its player is online (unless an operator says otherwise), within a cap of chunks per player, and let go of when it
 * moves on (only the chunks it left), when it's done, dismissed, knocked out or sent away with its player.
 *
 * <p>The tickets go through the mod's chunk queue (ChunkLoad: forcing is never a world-generation stall) under an owner
 * of its own, derived from the assistant's, so its window never tangles with anything else's.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PaChunks {

    private PaChunks() {}

    /** The high half of every ticket owner of ours: how leftovers from an earlier session are told apart. */
    static final long MAGIC = 0x7061436875_6E6B5AL;

    /** What it holds: which world, the chunk it centres on, the radius, the chunks. */
    public record Held(ResourceKey<Level> dim, int cx, int cz, int r, UUID ticket, UUID owner) {
        int count() { return (2 * r + 1) * (2 * r + 1); }
    }

    /** Every assistant's window, by its entity (for the cap per player). */
    private static final Map<UUID, Held> HELD = new HashMap<>();
    /** Let go of on the next server tick (never from inside a chunk unloading). */
    private static final List<Runnable> LATER = new ArrayList<>();
    private static boolean swept;

    public static UUID ticketOwner(UUID entity) {
        return new UUID(MAGIC, entity.getMostSignificantBits() ^ entity.getLeastSignificantBits());
    }

    /** Its reason to hold the ground, or null for none. */
    @Nullable
    static String reason(PlayerAssistantEntity a) {
        if (a.mind.come != null) return "walking to you";
        if (a.getMode() == AssistantEntity.Mode.STAY) return "staying put";
        if (a.mind.post != null) return "on guard";
        if (a.task != null || !a.plan.idle()) return "at work";
        return null;
    }

    static boolean ownerOnline(PlayerAssistantEntity a) {
        UUID o = a.getOwnerId();
        return o != null && a.getServer() != null && a.getServer().getPlayerList().getPlayer(o) != null;
    }

    /** Every second, from its moves: the window it should have, kept. */
    static void tick(PlayerAssistantEntity a) {
        if (!(a.level() instanceof ServerLevel sl) || a.getOwnerId() == null) return;
        boolean want = PaConfig.chunksOn() && reason(a) != null && (ownerOnline(a) || PaConfig.chunksOffline());
        Held was = a.mind.chunks;
        if (!want) {
            if (was != null) release(a);
            return;
        }
        int cx = a.getBlockX() >> 4, cz = a.getBlockZ() >> 4;
        int r = radiusFor(a);
        if (r < 0) {
            if (was != null) release(a);
            return;
        }
        if (was != null && was.dim().equals(sl.dimension()) && was.cx() == cx && was.cz() == cz && was.r() == r) return;
        UUID ticket = ticketOwner(a.getUUID());
        Held now = new Held(sl.dimension(), cx, cz, r, ticket, a.getOwnerId());
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) ChunkLoad.forceOne(sl, ticket, cx + dx, cz + dz, true);
        if (was != null) {                                                // only the chunks it has left
            ServerLevel old = a.getServer().getLevel(was.dim());
            if (old != null) {
                for (int dx = -was.r(); dx <= was.r(); dx++) for (int dz = -was.r(); dz <= was.r(); dz++) {
                    int x = was.cx() + dx, z = was.cz() + dz;
                    if (old == sl && Math.abs(x - cx) <= r && Math.abs(z - cz) <= r) continue;
                    ChunkLoad.forceOne(old, ticket, x, z, false);
                }
            }
        }
        a.mind.chunks = now;
        HELD.put(a.getUUID(), now);
    }

    /** The radius it may have: the server's, made smaller to fit its player's cap (-1: none at all). */
    static int radiusFor(PlayerAssistantEntity a) {
        int others = 0;
        for (Map.Entry<UUID, Held> e : HELD.entrySet()) {
            if (!e.getKey().equals(a.getUUID()) && e.getValue().owner().equals(a.getOwnerId())) others += e.getValue().count();
        }
        int cap = PaConfig.chunkCap();
        for (int r = PaConfig.chunkRadius(); r >= 0; r--) if (others + (2 * r + 1) * (2 * r + 1) <= cap) return r;
        return -1;
    }

    /** Let go of its whole window, now. */
    public static void release(PlayerAssistantEntity a) {
        Held h = a.mind.chunks;
        a.mind.chunks = null;
        if (h != null) HELD.remove(a.getUUID(), h);
        if (h == null || a.getServer() == null) return;
        ServerLevel lv = a.getServer().getLevel(h.dim());
        if (lv == null) return;
        for (int dx = -h.r(); dx <= h.r(); dx++) for (int dz = -h.r(); dz <= h.r(); dz++) ChunkLoad.forceOne(lv, h.ticket(), h.cx() + dx, h.cz() + dz, false);
    }

    /** Leaving the world (dismissed, knocked out, sent away with its player, unloaded): let go of on the next tick. */
    static void removed(PlayerAssistantEntity a) {
        if (a.mind.chunks == null) return;
        synchronized (LATER) { LATER.add(() -> release(a)); }
    }

    /** For the panel: what it's holding, in a few words ("" for nothing). */
    public static String describe(PlayerAssistantEntity a) {
        Held h = a.mind.chunks;
        if (h == null) return "";
        String why = reason(a);
        return h.count() + " chunk" + (h.count() == 1 ? "" : "s") + " kept awake round it" + (why == null ? "" : " (" + why + ")");
    }

    /** Tests: the window it holds (null for none). */
    @Nullable
    public static Held heldForTests(PlayerAssistantEntity a) {
        return a.mind.chunks;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (!LATER.isEmpty()) {
            List<Runnable> run;
            synchronized (LATER) { run = new ArrayList<>(LATER); LATER.clear(); }
            for (Runnable r : run) r.run();
        }
        if (e.getServer().getTickCount() == 40) PaArchery.at30();          // the level table's bow column, worked out off-thread in good time
        // a while after the world opens: tickets of ours saved from an earlier session, that nobody alive holds, let go
        if (!swept && e.getServer().getTickCount() > 100) {
            swept = true;
            java.util.Set<UUID> live = new java.util.HashSet<>();
            for (Held h : HELD.values()) live.add(h.ticket());
            ChunkLoad.releaseOwners(e.getServer(), u -> u.getMostSignificantBits() == MAGIC && !live.contains(u));
        }
    }

    @SubscribeEvent
    public static void onStopping(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        HELD.clear();
        synchronized (LATER) { LATER.clear(); }
        swept = false;
    }
}
