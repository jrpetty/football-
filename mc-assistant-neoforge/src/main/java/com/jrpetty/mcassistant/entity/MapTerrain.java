package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [borders-map] The lie of the land as the war room's map table draws it: every chunk anybody has had loaded, looked at
 * from above four blocks to a pixel, in the colours a vanilla map would give it (grass green, water blue by its depth,
 * sand, snow, stone, the roofs of the town), shaded by the slope as a map shades it. Kept with the world, so a map
 * drawn before a restart is there after it, and the farther country fills in as players, scouts and caravans pass.
 *
 * <p><b>What it costs.</b> A chunk is looked at once when it comes into memory (a chunk load queues it; sixteen columns
 * read off its own height map, a few microseconds) and again now and then while it stays (the ground round each town,
 * a few chunks a second, so new roofs and fields show). Never a chunk not already loaded: asking for one would load or
 * generate it on the server thread. Sixteen bytes a chunk on disk; a world of twenty thousand explored chunks is
 * about a third of a megabyte. Past {@link #MOST} chunks the farthest from every town are let go first.
 *
 * <p>The territory (Borders) reads it too: water and bare rock are harder ground to hold than grass.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class MapTerrain extends SavedData {

    private static final String ID = "mc_assistant_terrain";
    /** Samples a side in a chunk: one every four blocks. */
    public static final int SIDE = 4;
    /** A tile of the map: so many chunks a side (64 pixels at four blocks a pixel). */
    public static final int TILE = 16;
    /** The most chunks a world keeps. */
    static final int MOST = 250_000;
    /** Chunks looked at a tick, at most, from the queue of newly loaded ones. */
    static final int PER_TICK = 24;
    /** A chunk looked at again only after this long, if it comes into memory again (ticks). */
    static final long FRESH = 12000L;

    /** Chunk (ChunkPos.asLong) to its sixteen samples, row by row from the north-west. */
    private final Long2ObjectOpenHashMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
    /** When each chunk was last looked at this session (not kept: a restart looks again). */
    private final Long2LongOpenHashMap lookedAt = new Long2LongOpenHashMap();
    /** Bumped for every tile changed, so a client knows its copy is stale. */
    private final Long2LongOpenHashMap tileStamp = new Long2LongOpenHashMap();
    private long stamp;

    private MapTerrain() {}

    /** Chunks waiting to be looked at, by level. */
    private static final Map<ServerLevel, LongLinkedOpenHashSet> QUEUE = new ConcurrentHashMap<>();
    @Nullable private static MapTerrain cached;
    @Nullable private static ServerLevel cachedFor;

    public static MapTerrain of(ServerLevel level) {
        if (cachedFor == level && cached != null) return cached;
        MapTerrain t = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MapTerrain::new, MapTerrain::load, null), ID);
        cached = t;
        cachedFor = level;
        return t;
    }

    public static void resetForTests(ServerLevel level) {
        MapTerrain t = of(level);
        t.chunks.clear();
        t.lookedAt.clear();
        t.tileStamp.clear();
        QUEUE.remove(level);
        t.setDirty();
    }

    /** The server gone (a world closed in single player): nothing held over to the next. */
    @SubscribeEvent
    public static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        QUEUE.clear();
        cached = null;
        cachedFor = null;
    }

    // ------------------------------------------------------------------ looking

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk)) return;
        LongLinkedOpenHashSet q = QUEUE.computeIfAbsent(level, k -> new LongLinkedOpenHashSet());
        synchronized (q) {
            if (q.size() < 20000) q.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        Guard.run("map terrain", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                LongLinkedOpenHashSet q = QUEUE.get(level);
                if (q != null && !q.isEmpty()) drain(level, q, PER_TICK);
                if (tick % 20 == 7) refreshRound(level, tick / 20);
            }
        });
    }

    private static void drain(ServerLevel level, LongLinkedOpenHashSet q, int most) {
        MapTerrain t = of(level);
        long now = level.getGameTime();
        for (int i = 0; i < most; i++) {
            long key;
            synchronized (q) {
                if (q.isEmpty()) return;
                key = q.removeFirstLong();
            }
            if (t.chunks.containsKey(key) && now - t.lookedAt.getOrDefault(key, -FRESH) < FRESH) continue;
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (c != null) t.look(level, c, now);
        }
    }

    /**
     * The ground round each town looked at again, a few chunks a second in turn, so what the town builds and sows
     * shows on the map while its chunks stay in memory (they never unload, so a load never queues them again).
     */
    private static void refreshRound(ServerLevel level, int second) {
        MapTerrain t = of(level);
        long now = level.getGameTime();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int r = 8, side = 2 * r + 1;
            int start = Math.floorMod(second * 6 + v.id().hashCode(), side * side);
            for (int k = 0; k < 6; k++) {
                int i = (start + k) % (side * side);
                int cx = (v.centre().getX() >> 4) - r + i % side, cz = (v.centre().getZ() >> 4) - r + i / side;
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c != null) t.look(level, c, now);
            }
        }
    }

    /** One chunk's sixteen samples read off its height map. */
    void look(ServerLevel level, LevelChunk c, long now) {
        int bx = c.getPos().getMinBlockX(), bz = c.getPos().getMinBlockZ();
        byte[] out = new byte[SIDE * SIDE];
        int step = 16 / SIDE;
        for (int sz = 0; sz < SIDE; sz++) {
            for (int sx = 0; sx < SIDE; sx++) {
                int lx = sx * step + 1, lz = sz * step + 1;
                out[sz * SIDE + sx] = sample(level, c, bx, bz, lx, lz, step);
            }
        }
        long key = c.getPos().toLong();
        byte[] was = chunks.get(key);
        lookedAt.put(key, now);
        if (was != null && java.util.Arrays.equals(was, out)) return;
        if (was == null && chunks.size() >= MOST) return;           // full: the farther country stays as it was
        chunks.put(key, out);
        tileStamp.put(tileKey(c.getPos().x, c.getPos().z), ++stamp);
        setDirty();
    }

    /** The packed map colour of one column: the top block's colour and the slope's shade (the way a map draws it). */
    static byte sample(ServerLevel level, LevelChunk c, int bx, int bz, int lx, int lz, int step) {
        int y = c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
        if (y <= level.getMinBuildHeight()) return 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(bx + lx, y, bz + lz);
        BlockState st = c.getBlockState(p);
        MapColor colour = st.getMapColor(level, p);
        int guard = 0;
        while (colour == MapColor.NONE && p.getY() > level.getMinBuildHeight() && guard++ < 8) {
            p.move(0, -1, 0);
            st = c.getBlockState(p);
            colour = st.getMapColor(level, p);
        }
        if (colour == MapColor.NONE) return 0;
        MapColor.Brightness b;
        if (colour == MapColor.WATER) {
            int depth = 0;
            BlockPos.MutableBlockPos d = p.mutable();
            while (depth < 12 && d.getY() > level.getMinBuildHeight() && !c.getFluidState(d).isEmpty()) { d.move(0, -1, 0); depth++; }
            double shade = depth * 0.1 + ((lx + lz) & 1) * 0.2;
            b = shade < 0.5 ? MapColor.Brightness.HIGH : shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        } else {
            int north = lz - step >= 0 ? c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz - step) : y;
            double d = (y - north) * 0.8 + (((lx + lz) >> 2 & 1) - 0.5) * 0.4;
            b = d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        return colour.getPackedId(b);
    }

    // ------------------------------------------------------------------ reading

    /** A chunk's samples, or null where nobody has looked. */
    @Nullable
    public byte[] chunk(int cx, int cz) {
        return chunks.get(ChunkPos.asLong(cx, cz));
    }

    public int size() {
        return chunks.size();
    }

    public static long tileKey(int cx, int cz) {
        return ChunkPos.asLong(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE));
    }

    /** When a tile last changed (0: never seen). */
    public long tileStamp(int tx, int tz) {
        return tileStamp.getOrDefault(ChunkPos.asLong(tx, tz), 0L);
    }

    /** What sort of ground a chunk is, for the territory: water, bare rock and snow, forest, open, or unknown. */
    public enum Ground { UNKNOWN, OPEN, FOREST, ROCK, WATER }

    public Ground ground(int cx, int cz) {
        byte[] s = chunk(cx, cz);
        if (s == null) return Ground.UNKNOWN;
        int water = 0, rock = 0, forest = 0;
        for (byte b : s) {
            MapColor m = MapColor.byId((b & 0xFF) >> 2);
            if (m == MapColor.WATER || m == MapColor.ICE) water++;
            else if (m == MapColor.STONE || m == MapColor.SNOW || m == MapColor.DEEPSLATE || m == MapColor.TERRACOTTA_WHITE) rock++;
            else if (m == MapColor.PLANT) forest++;
        }
        if (water >= 10) return Ground.WATER;
        if (rock >= 10) return Ground.ROCK;
        if (forest >= 9) return Ground.FOREST;
        return Ground.OPEN;
    }

    /** Tests: this chunk looked at now, as if it had just come into memory. */
    public static void lookForTests(ServerLevel level, int cx, int cz) {
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        if (c != null) of(level).look(level, c, level.getGameTime());
    }

    // ------------------------------------------------------------------ kept with the world

    public static MapTerrain load(CompoundTag tag, HolderLookup.Provider registries) {
        MapTerrain t = new MapTerrain();
        long[] keys = tag.getLongArray("keys");
        byte[] all = tag.getByteArray("samples");
        int n = SIDE * SIDE;
        for (int i = 0; i < keys.length && (i + 1) * n <= all.length; i++) {
            byte[] s = new byte[n];
            System.arraycopy(all, i * n, s, 0, n);
            t.chunks.put(keys[i], s);
        }
        return t;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        int n = SIDE * SIDE;
        long[] keys = new long[chunks.size()];
        byte[] all = new byte[chunks.size() * n];
        int i = 0;
        for (Long2ObjectOpenHashMap.Entry<byte[]> e : chunks.long2ObjectEntrySet()) {
            keys[i] = e.getLongKey();
            System.arraycopy(e.getValue(), 0, all, i * n, n);
            i++;
        }
        tag.putLongArray("keys", keys);
        tag.putByteArray("samples", all);
        return tag;
    }
}
