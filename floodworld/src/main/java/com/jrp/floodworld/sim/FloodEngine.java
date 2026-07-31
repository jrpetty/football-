package com.jrp.floodworld.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jrp.floodworld.Config;
import com.jrp.floodworld.FloodWorld;
import com.jrp.floodworld.flow.FloodWorldFluids;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Rain sampling, puddle formation and recession.
 *
 * <p>Everything here runs on the server thread inside {@link ServerTickEvent.Post}, so the
 * per-pass scratch state ({@link #CTX}) is deliberately shared and reused rather than
 * reallocated. The passes are written to touch the chunk source as few times as possible:
 * a column read costs one heightmap lookup once its chunk has been resolved, and the
 * 9x9 downhill scan resolves at most a handful of distinct chunks instead of one per column.
 */
public final class FloodEngine {

    public static final int MAX_LEVEL = 8;
    public static final TagKey<Block> RAIN_BLOCKED =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("floodworld", "rain_blocked"));

    /** Sentinel for "absent" in {@code FloodSavedData.flood()} (its default return value). */
    private static final int NO_CELL = -1;
    /** Sentinel for "absent" in {@code FloodSavedData.baseY()} (its default return value). */
    private static final int NO_BASE = Integer.MIN_VALUE;

    private static boolean paused = false;
    private static long tickCounter = 0L;
    private static boolean warnedCap = false;

    private static final Map<ResourceKey<Level>, Snapshot> RECESSION_SNAPSHOT = new HashMap<>();
    private static final Ctx CTX = new Ctx();

    private FloodEngine() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (paused) return;
        if (!Config.ENABLE_MOD.get()) return;
        if (!FloodWorld.flowingFluidsLoaded()) return;
        if (!FloodWorldFluids.isModifyingWater()) return;

        tickCounter++;

        // Both cadences are global, so resolve them once instead of once per dimension.
        boolean rainTick = tickCounter % Config.RAIN_TICK_INTERVAL.get() == 0L;
        boolean decayTick = tickCounter % Config.SATURATION_DECAY_TICKS.get() == 0L;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.isRaining()) {
                // Nothing to sample without players, and no reason to touch the save data.
                if (!rainTick || level.players().isEmpty()) continue;
                rainPass(level, getData(level));
            } else {
                FloodSavedData data = getData(level);
                recessionPass(level, data);
                if (decayTick) decayPass(data);
            }
        }
    }

    private static FloodSavedData getData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FloodSavedData.factory(), FloodSavedData.ID);
    }

    // ------------------------------------------------------------------ rain

    private static void rainPass(ServerLevel level, FloodSavedData data) {
        List<ServerPlayer> players = level.players();
        int playerCount = players.size();
        if (playerCount == 0) return;

        int radius = Config.RAIN_RADIUS.get();
        int samples = Config.RAIN_SAMPLES_PER_TICK.get();
        int span = radius * 2 + 1;

        Ctx c = CTX.bind(level, data).withRainConfig();
        RandomSource rand = level.getRandom();

        for (int p = 0; p < playerCount; p++) {
            BlockPos origin = players.get(p).blockPosition();
            int px = origin.getX();
            int pz = origin.getZ();
            for (int i = 0; i < samples; i++) {
                int x = px + rand.nextInt(span) - radius;
                int z = pz + rand.nextInt(span) - radius;
                sampleColumn(c, x, z);
            }
        }
        c.end();
    }

    /**
     * The checks below are the same set as before, ordered cheapest-first: they are all pure
     * reads, so the columns that survive are identical while most rejections now cost a single
     * section lookup instead of a biome/temperature sample.
     */
    private static void sampleColumn(Ctx c, int x, int z) {
        LevelChunk chunk = c.chunkAt(x, z);
        if (chunk == null) return;

        ServerLevel level = c.level;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos surface = c.probe.set(x, surfaceY, z);
        if (!level.isLoaded(surface)) return;

        int topY = surfaceY - 1;
        BlockState top = chunk.getBlockState(c.scratch.set(x, topY, z));
        if (top.is(BlockTags.LEAVES) || top.is(RAIN_BLOCKED)) return;

        if (!level.canSeeSky(surface)) return;

        Biome biome = level.getBiome(surface).value();
        if (biome.getPrecipitationAt(surface) != Biome.Precipitation.RAIN) return;

        int topWater = FloodWorldFluids.waterAmount(top);
        if (topWater > 0) {
            // Fill the top water block first, then spill into the air above it once it is full.
            int depositY = topWater < 8 ? topY : surfaceY;
            deposit(c, c.probe.set(x, depositY, z), c.buildupWater, surfaceY);
        } else if (adjacentToWater(c, x, topY, z)) {
            deposit(c, c.probe.set(x, surfaceY, z), c.buildupWater, surfaceY);
        } else {
            puddle(c, chunk, x, z, surfaceY);
        }
    }

    private static boolean adjacentToWater(Ctx c, int x, int y, int z) {
        return waterAt(c, x, y, z - 1)  // north
                || waterAt(c, x, y, z + 1)  // south
                || waterAt(c, x + 1, y, z)  // east
                || waterAt(c, x - 1, y, z); // west
    }

    private static boolean waterAt(Ctx c, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = c.scratch.set(x, y, z);
        LevelChunk chunk = c.chunkAt(x, z);
        // Fall back to the level for an unloaded neighbour so the original (chunk-loading)
        // behaviour at a chunk border is preserved exactly.
        return chunk != null
                ? FloodWorldFluids.waterAmount(chunk.getBlockState(pos)) > 0
                : FloodWorldFluids.getWaterAmount(c.level, pos) > 0;
    }

    /**
     * Find the lowest open column within {@code downhillSearchRadius} and charge its saturation
     * counter; once saturated, water is deposited there instead of at the hit column.
     *
     * <p>Scan order and tie-breaking are unchanged (first strict minimum, x-major then z-major),
     * so the chosen column is identical to before — only the per-column cost changed.
     *
     * <p>The scan compares raw {@code ChunkAccess} heightmap values instead of going through
     * {@code Level.getHeight} per column. Those differ only by a constant, so the column that
     * wins is the same; the winner's absolute Y is then read back through the level, which is
     * one lookup per puddle rather than one per scanned column.
     */
    private static void puddle(Ctx c, LevelChunk originChunk, int x, int z, int surfaceY) {
        int r = c.downhillRadius;
        int bestX = x;
        int bestZ = z;
        int bestRaw = originChunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);

        for (int dx = -r; dx <= r; dx++) {
            int cx = x + dx;
            int localX = cx & 15;
            boolean originColumn = dx == 0;
            for (int dz = -r; dz <= r; dz++) {
                if (originColumn && dz == 0) continue;
                int cz = z + dz;
                LevelChunk chunk = c.chunkAt(cx, cz);
                if (chunk == null) continue;
                int cy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, cz & 15);
                if (cy >= bestRaw) continue;
                bestRaw = cy;
                bestX = cx;
                bestZ = cz;
            }
        }

        int bestSurface = (bestX == x && bestZ == z)
                ? surfaceY
                : c.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bestX, bestZ);

        FloodSavedData data = c.data;
        long colKey = columnKey(bestX, bestZ);
        int threshold = c.saturationThreshold;
        if (threshold > 0) {
            Long2IntOpenHashMap sat = data.saturation();
            int hits = Math.min(threshold, sat.get(colKey) + 1);
            sat.put(colKey, hits);
            data.setDirty();
            if (hits < threshold) return;
        }

        deposit(c, c.probe.set(bestX, bestSurface, bestZ), c.buildupLand, bestSurface);
    }

    private static void deposit(Ctx c, BlockPos pos, int rate, int columnSurfaceY) {
        ServerLevel level = c.level;
        if (!level.isLoaded(pos)) return;

        FloodSavedData data = c.data;
        Long2IntOpenHashMap baseMap = data.baseY();
        long colKey = columnKey(pos.getX(), pos.getZ());
        int base = baseMap.get(colKey);
        boolean baseKnown = base != NO_BASE;
        if (!baseKnown) base = columnSurfaceY - 1;

        if (pos.getY() > base + c.maxFloodHeight) return;

        LevelChunk chunk = c.chunkAt(pos.getX(), pos.getZ());
        int cur = chunk != null
                ? FloodWorldFluids.waterAmount(chunk.getBlockState(pos))
                : FloodWorldFluids.getWaterAmount(level, pos);

        Long2IntOpenHashMap flood = data.flood();
        long k = pos.asLong();
        // Single lookup: the map's default return value is -1 and stored levels are 0-8.
        int tracked = flood.get(k);

        if (cur >= 8) {
            track(data, flood, baseMap, k, colKey, base, baseKnown, tracked, cur);
            return;
        }

        if (tracked == NO_CELL && flood.size() >= c.maxActiveCells) {
            if (!warnedCap) {
                FloodWorld.LOGGER.warn(
                        "FloodWorld: maxActiveCells ({}) reached in {} — new flood cells are being skipped.",
                        Config.MAX_ACTIVE_CELLS.get(), level.dimension().location());
                warnedCap = true;
            }
            return;
        }

        int target = Math.min(8, cur + rate);
        // The Flowing Fluids API is external: never hand it a mutable position.
        boolean changed = FloodWorldFluids.setWaterAmount(level, pos.immutable(), target);
        if (changed || cur > 0) {
            track(data, flood, baseMap, k, colKey, base, baseKnown, tracked, cur);
        }
    }

    /** Remember the pre-flood water level for a cell so recession can restore it exactly. */
    private static void track(FloodSavedData data, Long2IntOpenHashMap flood, Long2IntOpenHashMap baseMap,
                              long k, long colKey, int base, boolean baseKnown, int tracked, int originalLevel) {
        if (tracked != NO_CELL) return;
        flood.put(k, originalLevel);
        if (!baseKnown) baseMap.put(colKey, base);
        data.setDirty();
    }

    // ------------------------------------------------------------- recession

    private static void recessionPass(ServerLevel level, FloodSavedData data) {
        Long2IntOpenHashMap flood = data.flood();
        if (flood.isEmpty()) {
            if (!data.baseY().isEmpty()) {
                data.baseY().clear();
                data.setDirty();
            }
            RECESSION_SNAPSHOT.remove(level.dimension());
            return;
        }

        int evap = Config.EVAP_TICKS.get();
        long[] snap = refreshSnapshot(level, flood, evap);
        if (snap.length == 0) return;

        int sampleCount = Math.max(1, (snap.length + evap - 1) / evap);
        RandomSource rand = level.getRandom();
        Ctx c = CTX.bind(level, data);
        BlockPos.MutableBlockPos pos = c.probe;
        boolean dirty = false;

        for (int i = 0; i < sampleCount; i++) {
            long k = snap[rand.nextInt(snap.length)];
            int original = flood.get(k);
            if (original == NO_CELL) continue;

            int bx = BlockPos.getX(k);
            int bz = BlockPos.getZ(k);
            pos.set(bx, BlockPos.getY(k), bz);
            if (!level.isLoaded(pos)) continue;

            LevelChunk chunk = c.chunkAt(bx, bz);
            int cur = chunk != null
                    ? FloodWorldFluids.waterAmount(chunk.getBlockState(pos))
                    : FloodWorldFluids.getWaterAmount(level, pos);

            dirty = true;
            if (cur <= original) {
                flood.remove(k);
                continue;
            }
            int target = cur - 1;
            FloodWorldFluids.setWaterAmount(level, pos.immutable(), target);
            if (target <= original) flood.remove(k);
        }

        c.end();
        if (dirty) data.setDirty();
    }

    /**
     * The drain pass walks a snapshot of the tracked cells rather than the live map, rebuilt
     * every {@code evapTicks}. Snapshot and its rebuild tick live in one entry so the lookup
     * costs a single map probe and no {@link Long} boxing.
     */
    private static long[] refreshSnapshot(ServerLevel level, Long2IntOpenHashMap flood, int evap) {
        ResourceKey<Level> dim = level.dimension();
        Snapshot s = RECESSION_SNAPSHOT.get(dim);
        if (s == null) {
            s = new Snapshot();
            RECESSION_SNAPSHOT.put(dim, s);
        }
        if (s.keys == null || tickCounter - s.tick >= evap) {
            s.keys = flood.keySet().toLongArray();
            s.tick = tickCounter;
        }
        return s.keys;
    }

    private static void decayPass(FloodSavedData data) {
        Long2IntOpenHashMap sat = data.saturation();
        if (sat.isEmpty()) return;

        ObjectIterator<Long2IntMap.Entry> it = sat.long2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2IntMap.Entry e = it.next();
            int v = e.getIntValue() - 1;
            if (v <= 0) {
                it.remove();
            } else {
                e.setValue(v);
            }
        }
        data.setDirty();
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    // ----------------------------------------------------------- command API

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean value) {
        paused = value;
    }

    public static int totalFloodCells(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += getData(level).flood().size();
        }
        return total;
    }

    public static int totalSaturationColumns(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += getData(level).saturation().size();
        }
        return total;
    }

    public static int clearAll(MinecraftServer server) {
        int removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ServerLevel level : server.getAllLevels()) {
            FloodSavedData data = getData(level);
            Long2IntOpenHashMap flood = data.flood();
            if (flood.isEmpty()) continue;

            for (long k : flood.keySet().toLongArray()) {
                pos.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
                if (!level.isLoaded(pos)) continue;
                FloodWorldFluids.setWaterAmount(level, pos.immutable(), flood.get(k));
                flood.remove(k);
                removed++;
            }
            data.baseY().clear();
            RECESSION_SNAPSHOT.remove(level.dimension());
            data.setDirty();
        }
        CTX.invalidate();
        warnedCap = false;
        return removed;
    }

    // --------------------------------------------------------- pass scratch

    /** Per-dimension recession snapshot plus the tick it was taken on. */
    private static final class Snapshot {
        long[] keys;
        long tick = -1L;
    }

    /**
     * Scratch state reused across passes. The server tick is single-threaded and passes never
     * overlap, so this avoids re-reading config values and re-allocating positions per sample.
     */
    private static final class Ctx {
        final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        ServerLevel level;
        FloodSavedData data;
        ServerChunkCache source;

        int buildupWater;
        int buildupLand;
        int maxFloodHeight;
        int maxActiveCells;
        int downhillRadius;
        int saturationThreshold;

        /**
         * Small direct-lookup chunk cache. A downhill scan spans at most a handful of chunks,
         * and consecutive columns almost always share one, so the last-hit slot resolves nearly
         * every lookup with a single long compare.
         */
        private static final int CAP = 16;
        /** {@code ChunkPos.asLong} can never produce this (chunk Z of -2^31). */
        private static final long NO_KEY = Long.MIN_VALUE;

        private final long[] keys = new long[CAP];
        private final LevelChunk[] chunks = new LevelChunk[CAP];
        private int size;
        private long lastKey = NO_KEY;
        private LevelChunk lastChunk;

        /** Bind to a level for one pass. Cheap: no config reads. */
        Ctx bind(ServerLevel level, FloodSavedData data) {
            this.level = level;
            this.data = data;
            this.source = level.getChunkSource();
            invalidate();
            return this;
        }

        /** Additionally snapshot the values the rain pass needs, so samples don't re-read them. */
        Ctx withRainConfig() {
            this.buildupWater = Config.BUILDUP_WATER.get();
            this.buildupLand = Config.BUILDUP_LAND.get();
            this.maxFloodHeight = Config.MAX_FLOOD_HEIGHT.get();
            this.maxActiveCells = Config.MAX_ACTIVE_CELLS.get();
            this.downhillRadius = Config.DOWNHILL_SEARCH_RADIUS.get();
            this.saturationThreshold = Config.SATURATION_THRESHOLD.get();
            return this;
        }

        /** Drop chunk references so an unloaded chunk cannot be pinned between passes. */
        void end() {
            this.level = null;
            this.data = null;
            this.source = null;
            invalidate();
        }

        void invalidate() {
            for (int i = 0; i < CAP; i++) chunks[i] = null;
            size = 0;
            lastKey = NO_KEY;
            lastChunk = null;
        }

        /** The loaded chunk containing block column (x, z), or {@code null} if it is not loaded. */
        LevelChunk chunkAt(int blockX, int blockZ) {
            int cx = blockX >> 4;
            int cz = blockZ >> 4;
            long key = ChunkPos.asLong(cx, cz);
            if (key == lastKey) return lastChunk;
            return resolve(key, cx, cz);
        }

        private LevelChunk resolve(long key, int cx, int cz) {
            for (int i = 0; i < size; i++) {
                if (keys[i] != key) continue;
                LevelChunk hit = chunks[i];
                lastKey = key;
                lastChunk = hit;
                return hit;
            }
            // Same pair of calls the original made per column, just once per chunk.
            LevelChunk chunk = source.hasChunk(cx, cz) ? level.getChunk(cx, cz) : null;
            if (size == CAP) {
                size = 0;
            }
            keys[size] = key;
            chunks[size] = chunk;
            size++;
            lastKey = key;
            lastChunk = chunk;
            return chunk;
        }
    }
}
