package com.jrpetty.mcassistant;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Station chunk loading. A stationed assistant keeps a small square of chunks
 * around its post force-loaded and fully ticking, so crops grow, furnaces
 * burn, saplings regrow, and the specialist keeps working while the player is
 * hundreds of blocks away. Tickets are owned by the assistant's UUID, persist
 * with the world save (the station resumes ticking after a reload before the
 * player ever comes back), and are released when the bot stands down or dies.
 *
 * <h2>Why forcing is queued</h2>
 * NeoForge's {@code forceChunk} loads the chunk <em>on the calling thread</em>
 * the moment a ticket is added (it copies vanilla's {@code setChunkForced}).
 * A village keeps a ring of up to seventeen by seventeen chunks awake, and asked
 * for all of it in one call, that is two hundred and eighty-nine chunks of
 * world generation on the server thread — measured on a real terrain server at
 * fourteen seconds with the whole game frozen, on the first settler placed. It
 * happened again every time the village grew a ring.
 *
 * <p>So a request here only writes down what it wants. Each tick the queue does
 * two cheap things: chunks that are already loaded are forced at once (a chunk
 * on hand costs nothing to force), and a few that are not are <em>asked for</em>
 * the vanilla way — a plain ticket, which the world generator answers on its
 * own threads while the game carries on. When one arrives it is forced, and the
 * plain ticket is dropped. Nearest the centre first, so a village is awake at
 * its heart within a moment and fully awake within a minute.
 */
public final class ChunkLoad {

    public static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "stations"), null);

    private static final Logger LOG = LogUtils.getLogger();

    /** How many chunks may be being generated on our behalf at once. */
    private static final int IN_FLIGHT = 4;
    /** Chunks already on hand are forced this many a tick: it costs microseconds. */
    private static final int FORCED_PER_TICK = 64;
    /** How much of the queue one tick looks at. */
    private static final int SCAN_PER_TICK = 600;
    /** A chunk that has not come in this long is forced the hard way, one a tick. */
    private static final int PATIENCE_TICKS = 2400;

    /** A plain ticket that asks the world to load one chunk, without waiting for it. */
    private static final TicketType<ChunkPos> ASK = TicketType.create(
        McAssistantMod.MODID + ":ask", Comparator.comparingLong(ChunkPos::toLong));

    private record Key(ResourceKey<Level> dimension, UUID owner, long chunk) {}

    private static final class Want {
        final ServerLevel level;
        final UUID owner;
        final int cx;
        final int cz;
        final long since;
        boolean asked;

        Want(ServerLevel level, UUID owner, int cx, int cz) {
            this.level = level;
            this.owner = owner;
            this.cx = cx;
            this.cz = cz;
            this.since = level.getGameTime();
        }

        ChunkPos pos() { return new ChunkPos(cx, cz); }
    }

    /** What is still to be forced, nearest the centre first (insertion order). */
    private static final LinkedHashMap<Key, Want> WANTED = new LinkedHashMap<>();
    /** What has been forced already. A village asks for its whole ring again every
     *  time it grows, and every folk asks for its own patch every time it changes
     *  its mind; without this each ask queued the whole square afresh. */
    private static final java.util.HashSet<Key> FORCED = new java.util.HashSet<>();
    private static int asking;
    private static long lastReport;

    private ChunkLoad() {}

    public static void onRegisterControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /** Forget everything queued (the world is closing or another is opening). */
    public static void reset() {
        WANTED.clear();
        FORCED.clear();
        asking = 0;
        lastReport = 0;
    }

    /** How many chunks are still waiting to be forced for this owner. */
    public static int pending(UUID owner) {
        int n = 0;
        for (Key k : WANTED.keySet()) if (k.owner().equals(owner)) n++;
        return n;
    }

    /** Force (on=true) or release (on=false) a (2r+1)x(2r+1) square of fully
     *  ticking chunks around {@code center}, owned by {@code owner}. Forcing is
     *  queued and happens over the next few seconds; releasing is at once. */
    public static void setLoaded(ServerLevel level, UUID owner, BlockPos center, int radius, boolean on) {
        // Releasing always goes through, even with the feature switched off —
        // otherwise flipping the config mid-world would strand live tickets.
        if (on && !AssistantConfig.chunkLoading()) return;
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        if (!on) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Key key = new Key(level.dimension(), owner, ChunkPos.asLong(cx + dx, cz + dz));
                    FORCED.remove(key);
                    Want w = WANTED.remove(key);
                    if (w != null) dropAsk(w);
                    CONTROLLER.forceChunk(level, owner, cx + dx, cz + dz, false, true);
                }
            }
            return;
        }
        // The centre first, then outward: the heart of a village is awake in a
        // moment and its far fields follow.
        List<int[]> cells = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) cells.add(new int[] {dx, dz});
        }
        cells.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        for (int[] c : cells) {
            Key key = new Key(level.dimension(), owner, ChunkPos.asLong(cx + c[0], cz + c[1]));
            if (FORCED.contains(key) || WANTED.containsKey(key)) continue;
            WANTED.put(key, new Want(level, owner, cx + c[0], cz + c[1]));
        }
    }

    private static void dropAsk(Want w) {
        if (!w.asked) return;
        w.asked = false;
        asking = Math.max(0, asking - 1);
        ChunkPos pos = w.pos();
        w.level.getChunkSource().removeRegionTicket(ASK, pos, 0, pos);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (WANTED.isEmpty()) return;
        int forced = 0;
        boolean hard = false;
        int waiting = 0;
        int scanned = 0;
        List<Key> done = new ArrayList<>();
        for (java.util.Map.Entry<Key, Want> e : WANTED.entrySet()) {
            if (forced >= FORCED_PER_TICK || scanned++ >= SCAN_PER_TICK) break;
            Want w = e.getValue();
            boolean loaded = w.level.getChunkSource().getChunkNow(w.cx, w.cz) != null;
            boolean overdue = !loaded && w.level.getGameTime() - w.since > PATIENCE_TICKS;
            if (loaded || (overdue && !hard)) {
                long t0 = System.nanoTime();
                CONTROLLER.forceChunk(w.level, w.owner, w.cx, w.cz, true, true);
                // Only now let go of the plain ticket: the forced one holds the chunk.
                dropAsk(w);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms > 200) {
                    LOG.warn("[MCA-STALL] forcing chunk {},{} took {} ms{}", w.cx, w.cz, ms,
                        loaded ? "" : " (it never arrived; loaded the hard way)");
                }
                done.add(e.getKey());
                forced++;
                if (!loaded) hard = true;
            } else {
                waiting++;
                if (!w.asked && asking < IN_FLIGHT) {
                    w.asked = true;
                    asking++;
                    ChunkPos pos = w.pos();
                    w.level.getChunkSource().addRegionTicket(ASK, pos, 0, pos);
                }
            }
        }
        for (Key k : done) {
            WANTED.remove(k);
            FORCED.add(k);
        }
        long now = event.getServer().getTickCount();
        if (!WANTED.isEmpty() && now - lastReport >= 1200) {
            lastReport = now;
            Want first = WANTED.values().iterator().next();
            LOG.info("[MCA-CHUNKS] {} chunks waiting ({} of them not yet loaded), {} being generated, {} forced so far;"
                    + " oldest waiting {},{} for {} ticks (loaded={}, asked={})",
                WANTED.size(), waiting, asking, FORCED.size(), first.cx, first.cz,
                first.level.getGameTime() - first.since,
                first.level.getChunkSource().getChunkNow(first.cx, first.cz) != null, first.asked);
        }
    }
}
