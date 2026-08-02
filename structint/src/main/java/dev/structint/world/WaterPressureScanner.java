package dev.structint.world;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.structint.Config;
import dev.structint.StructuralIntegrityMod;
import dev.structint.core.HydroLoad;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Water pressure against player-built walls.
 *
 * <p>Water level changes fire no block event that reaches us reliably, and a reservoir filling up
 * changes the load on a dam without anyone touching it, so - exactly like {@link SnowLoadScanner}
 * - laden blocks are re-checked by a periodic budgeted sweep near players.
 *
 * <p>Only blocks a player placed are ever examined or broken. Natural terrain is the abutment the
 * thrust flows into: a run that reaches unmanaged ground is anchored and never fails, which is why
 * keying a dam into the valley walls works.
 *
 * <p>The rules a player can learn from playing:
 * <ul>
 *   <li>Deeper water pushes harder, linearly. A dam wants to be thicker at the bottom.</li>
 *   <li>Thickness adds up. Each block back from the wet face contributes its own resistance.</li>
 *   <li>Buttresses behind the wall lend their surplus sideways, less so the further away.</li>
 *   <li>A wall bowed into the water throws thrust into its flanks and holds far more.</li>
 *   <li>Water on both sides cancels - a flooded chamber is safe, an air-filled one is not.</li>
 * </ul>
 */
public final class WaterPressureScanner {

    private static final Map<ServerLevel, WaterPressureScanner> SCANNERS = new IdentityHashMap<>();
    private static final int MAX_REMEMBERED = 100_000;

    /** Last reported stress tier per position, so a groaning dam does not re-warn every sweep. */
    private final Long2ByteOpenHashMap tiers = new Long2ByteOpenHashMap();
    private final List<Survey> surveys = new ArrayList<>();
    private int ticksSinceSweep;
    private int chunkCursor;

    private WaterPressureScanner() {
        tiers.defaultReturnValue((byte) 0);
    }

    public static void tick(ServerLevel level) {
        if (!Config.HYDRO_ENABLE.get()) {
            SCANNERS.remove(level);
            return;
        }
        SCANNERS.computeIfAbsent(level, l -> new WaterPressureScanner()).sweep(level);
    }

    public static void forget(ServerLevel level) {
        SCANNERS.remove(level);
    }

    // ------------------------------------------------------------------- sweep

    private void sweep(ServerLevel level) {
        if (++ticksSinceSweep < Config.HYDRO_SCAN_INTERVAL_TICKS.get()) return;
        ticksSinceSweep = 0;

        if (tiers.size() > MAX_REMEMBERED) tiers.clear();

        LongArrayList chunks = candidateChunks(level);
        if (chunks.isEmpty()) {
            if (!tiers.isEmpty()) tiers.clear();
            return;
        }

        WaterPressure.Probe probe = new WaterPressure.Probe(level);
        List<Candidate> stressed = new ArrayList<>();

        int budget = Config.HYDRO_SCAN_BUDGET.get();
        int size = chunks.size();
        int start = Math.floorMod(chunkCursor, size);
        for (int i = 0; i < size && budget > 0; i++) {
            int index = (start + i) % size;
            chunkCursor = index + 1;
            budget -= scanChunk(level, probe, chunks.getLong(index), budget, stressed);
        }

        repaintSurveys(level, probe);
        probe.clear();
        if (!stressed.isEmpty()) resolve(level, stressed);
    }

    private static LongArrayList candidateChunks(ServerLevel level) {
        int radius = Config.HYDRO_SCAN_RADIUS_CHUNKS.get();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList out = new LongArrayList();
        for (ServerPlayer player : level.players()) {
            ChunkPos centre = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long key = ChunkPos.asLong(centre.x + dx, centre.z + dz);
                    if (seen.add(key)) out.add(key);
                }
            }
        }
        return out;
    }

    private int scanChunk(ServerLevel level, WaterPressure.Probe probe, long chunkKey,
                          int budget, List<Candidate> stressed) {
        LevelChunk chunk = StructuralData.loadedChunk(level, ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        if (chunk == null) return 0;
        ManagedBlocks managed = StructuralData.managed(chunk);
        if (managed.isEmpty()) return 0;

        int examined = 0;
        LongIterator it = managed.view().iterator();
        while (it.hasNext() && examined < budget) {
            long packed = it.nextLong();
            examined++;
            WaterPressure.Reading r = evaluate(probe, BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (r.stress() > 0.0) stressed.add(new Candidate(packed, r.stress()));
        }
        return examined;
    }

    // -------------------------------------------------------------- evaluation

    /**
     * Fraction of its capacity this block is using, or 0 if no water bears on it. Greater than or
     * equal to 1 means it fails.
     */
    static WaterPressure.Reading evaluate(WaterPressure.Probe probe, int x, int y, int z) {
        BlockState self = probe.stateAt(x, y, z);
        if (WaterPressure.isExempt(self)) return WaterPressure.Reading.NONE;
        if (self.isAir() || probe.isWaterAt(x, y, z)) return WaterPressure.Reading.NONE;

        double pressurePerBlock = Config.HYDRO_PRESSURE_PER_BLOCK.get();
        WaterPressure.Reading worst = WaterPressure.Reading.NONE;

        for (int[] d : WaterPressure.DIRECTIONS) {
            int wx = x + d[0];
            int wy = y + d[1];
            int wz = z + d[2];
            if (!probe.isLoadedAt(wx, wy, wz)) continue;
            if (!probe.isWaterAt(wx, wy, wz)) continue;

            int head = probe.headAbove(wx, wy, wz);
            if (head <= 0) continue;
            double body = HydroLoad.bodyFactor(probe.bodyVolume(wx, wy, wz),
                    Config.HYDRO_BODY_REFERENCE.get(),
                    Config.HYDRO_BODY_INFLUENCE.get(),
                    Config.HYDRO_BODY_MAX_FACTOR.get());
            double load = HydroLoad.load(head, pressurePerBlock, body);

            // Walk the wall away from the water: -d.
            Run run = walk(probe, x, y, z, -d[0], -d[1], -d[2]);
            if (run.anchored) continue;
            if (run.reliefHead > 0) {
                // Water on the far side too - only the difference has to be carried.
                load -= HydroLoad.load(run.reliefHead, pressurePerBlock, body);
            }
            if (load <= 0.0) continue;

            double brace = bracing(probe, x, y, z, d, run.capacity);
            int curve = archSetback(probe, x, y, z, d);
            double arch = HydroLoad.archMultiplier(curve,
                    Config.HYDRO_ARCH_GAIN.get(), Config.HYDRO_ARCH_MAX_SETBACK.get());
            double capacity = (run.capacity + brace) * arch;

            double stress = HydroLoad.stress(load, capacity);
            if (stress > worst.stress()) {
                worst = new WaterPressure.Reading(stress, head, probe.bodyVolume(wx, wy, wz), body,
                        load, run.capacity, brace, arch, curve, capacity);
            }
        }
        return worst;
    }

    /** One-off reading for a single block, for the inspection command. */
    public static WaterPressure.Reading inspect(ServerLevel level, BlockPos pos) {
        WaterPressure.Probe probe = new WaterPressure.Probe(level);
        WaterPressure.Reading r = evaluate(probe, pos.getX(), pos.getY(), pos.getZ());
        probe.clear();
        return r;
    }

    // ------------------------------------------------------------------ survey

    /** A temporary request to paint every straining block in a box so a player can see it. */
    private record Survey(int x, int y, int z, int radius, long expiresAt) {
    }

    /** What one survey pass found: how many blocks sit in each tier, and the worst one. */
    public record SurveyResult(int examined, int comfortable, int loaded, int straining, int failing,
                               double worstStress, int worstX, int worstY, int worstZ) {

        public boolean anyLoad() {
            return comfortable + loaded + straining + failing > 0;
        }
    }

    /**
     * Paint and count every water-loaded player-placed block in range, and keep repainting it for
     * {@code ticks} so the picture stays up while you walk the dam.
     */
    public static SurveyResult survey(ServerLevel level, BlockPos centre, int radius, int ticks) {
        WaterPressureScanner scanner = SCANNERS.computeIfAbsent(level, l -> new WaterPressureScanner());
        if (ticks > 0) {
            scanner.surveys.add(new Survey(centre.getX(), centre.getY(), centre.getZ(), radius,
                    level.getGameTime() + ticks));
        }
        WaterPressure.Probe probe = new WaterPressure.Probe(level);
        int[] tally = new int[5];
        double[] worst = {0.0};
        long[] worstPos = {centre.asLong()};
        int examined = paint(level, probe, centre.getX(), centre.getY(), centre.getZ(), radius, tally, worst, worstPos);
        probe.clear();
        return new SurveyResult(examined, tally[1], tally[2], tally[3], tally[4], worst[0],
                BlockPos.getX(worstPos[0]), BlockPos.getY(worstPos[0]), BlockPos.getZ(worstPos[0]));
    }

    private void repaintSurveys(ServerLevel level, WaterPressure.Probe probe) {
        if (surveys.isEmpty()) return;
        long now = level.getGameTime();
        surveys.removeIf(s -> s.expiresAt() < now);
        for (Survey s : surveys) {
            paint(level, probe, s.x(), s.y(), s.z(), s.radius(), null, null, null);
        }
    }

    /** Walk the managed blocks in a box, mark each by tier, and optionally tally what was seen. */
    private static int paint(ServerLevel level, WaterPressure.Probe probe, int cx, int cy, int cz,
                             int radius, int[] tally, double[] worst, long[] worstPos) {
        double weepAt = Config.HYDRO_WEEP_THRESHOLD.get();
        int budget = Config.HYDRO_SCAN_BUDGET.get();
        int chunkRadius = Math.max(1, (radius >> 4) + 1);
        int examined = 0;

        for (int dx = -chunkRadius; dx <= chunkRadius && budget > 0; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius && budget > 0; dz++) {
                LevelChunk chunk = StructuralData.loadedChunk(level, (cx >> 4) + dx, (cz >> 4) + dz);
                if (chunk == null) continue;
                ManagedBlocks managed = StructuralData.managed(chunk);
                if (managed.isEmpty()) continue;
                LongIterator it = managed.view().iterator();
                while (it.hasNext() && budget > 0) {
                    long packed = it.nextLong();
                    int bx = BlockPos.getX(packed);
                    int by = BlockPos.getY(packed);
                    int bz = BlockPos.getZ(packed);
                    if (Math.abs(bx - cx) > radius || Math.abs(by - cy) > radius || Math.abs(bz - cz) > radius) continue;
                    budget--;
                    examined++;
                    WaterPressure.Reading r = evaluate(probe, bx, by, bz);
                    int tier = r.tier(weepAt);
                    if (tier == 0) continue;
                    mark(level, bx, by, bz, tier);
                    if (tally != null) {
                        tally[tier]++;
                        if (r.stress() > worst[0]) {
                            worst[0] = r.stress();
                            worstPos[0] = BlockPos.asLong(bx, by, bz);
                        }
                    }
                }
            }
        }
        return examined;
    }

    /** Green means it is fine, blue means it is working, orange means it is straining, red goes. */
    private static void mark(ServerLevel level, int x, int y, int z, int tier) {
        if (effectsBroken) return;
        SimpleParticleType type = switch (tier) {
            case 4 -> ParticleTypes.LAVA;
            case 3 -> ParticleTypes.FLAME;
            case 2 -> ParticleTypes.DRIPPING_WATER;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
        try {
            level.sendParticles(type, x + 0.5, y + 0.5, z + 0.5, tier >= 3 ? 4 : 2, 0.25, 0.25, 0.25, 0.0);
        } catch (Throwable t) {
            noteEffectsBroken(t);
        }
    }

    /** Result of walking a wall away from the water. */
    private static final class Run {
        int capacity;
        boolean anchored;
        int reliefHead;
    }

    /**
     * Accumulate resistance from the wet face backwards until air (the dry side), water (relief) or
     * natural ground (anchored) is reached.
     */
    private static Run walk(WaterPressure.Probe probe, int x, int y, int z, int dx, int dy, int dz) {
        Run run = new Run();
        int maxThickness = Config.HYDRO_MAX_THICKNESS.get();
        for (int step = 0; step < maxThickness; step++) {
            int cx = x + dx * step;
            int cy = y + dy * step;
            int cz = z + dz * step;
            if (!probe.isLoadedAt(cx, cy, cz)) {
                run.anchored = true;
                return run;
            }
            if (probe.isWaterAt(cx, cy, cz)) {
                run.reliefHead = probe.headAbove(cx, cy, cz);
                return run;
            }
            BlockState state = probe.stateAt(cx, cy, cz);
            if (WaterPressure.isExempt(state)) {
                run.anchored = true;
                return run;
            }
            if (!probe.isWall(state, cx, cy, cz)) {
                return run; // dry side
            }
            if (!probe.isManagedAt(cx, cy, cz)) {
                run.anchored = true; // natural terrain takes the thrust
                return run;
            }
            run.capacity += WaterPressure.resistanceOf(state);
        }
        return run;
    }

    /** Surplus lent by a thicker section or buttress within reach, in the plane of the wall. */
    private static double bracing(WaterPressure.Probe probe, int x, int y, int z, int[] waterDir, int ownCapacity) {
        double share = Config.HYDRO_BRACING_SHARE.get();
        if (share <= 0.0) return 0.0;
        int reach = Config.HYDRO_BRACING_REACH.get();
        double best = 0.0;

        for (int[] l : perpendicular(waterDir)) {
            for (int dist = 1; dist <= reach; dist++) {
                int nx = x + l[0] * dist;
                int ny = y + l[1] * dist;
                int nz = z + l[2] * dist;
                if (!probe.isLoadedAt(nx, ny, nz)) break;
                BlockState state = probe.stateAt(nx, ny, nz);
                if (!probe.isWall(state, nx, ny, nz)) break;
                if (!probe.isManagedAt(nx, ny, nz)) break;
                Run neighbour = walk(probe, nx, ny, nz, -waterDir[0], -waterDir[1], -waterDir[2]);
                int cap = neighbour.anchored ? Config.HYDRO_MAX_THICKNESS.get() * Config.HYDRO_RES_SEALING.get()
                        : neighbour.capacity;
                double bonus = HydroLoad.bracingBonus(ownCapacity, cap, dist, share);
                if (bonus > best) best = bonus;
            }
        }
        return Math.min(best, Config.HYDRO_BRACING_MAX_BONUS.get());
    }

    /**
     * How far the wall sits back from this block on both flanks. Positive on a wall bowed into the
     * water, which is the shape that lets a thin arch dam hold. Horizontal faces only.
     */
    private static int archSetback(WaterPressure.Probe probe, int x, int y, int z, int[] waterDir) {
        if (waterDir[1] != 0) return 0;
        if (Config.HYDRO_ARCH_GAIN.get() <= 0.0) return 0;
        // Measured over a baseline, not against the immediate neighbours: a real arch is almost
        // flat block to block, so sampling +/-1 would score every genuine curve as straight.
        int baseline = Config.HYDRO_ARCH_BASELINE.get();
        int[][] flanks = horizontalPerpendicular(waterDir);
        int a = faceOffset(probe, x + flanks[0][0] * baseline, y, z + flanks[0][2] * baseline, waterDir);
        if (a == Integer.MIN_VALUE) return 0;
        int b = faceOffset(probe, x + flanks[1][0] * baseline, y, z + flanks[1][2] * baseline, waterDir);
        if (b == Integer.MIN_VALUE) return 0;
        return Math.min(a, b);
    }

    /**
     * How many blocks behind the reference column the wall's wet face sits at (x, y, z), scanning
     * along the water axis. Negative means it stands proud of the reference; MIN_VALUE means there
     * is no wall on this flank at all.
     */
    private static int faceOffset(WaterPressure.Probe probe, int x, int y, int z, int[] waterDir) {
        int range = Config.HYDRO_ARCH_SCAN_RANGE.get();
        for (int k = -range; k <= range; k++) {
            // k counts backwards from the water: the face is the solid block whose +waterDir is water.
            int cx = x - waterDir[0] * k;
            int cy = y - waterDir[1] * k;
            int cz = z - waterDir[2] * k;
            if (!probe.isLoadedAt(cx, cy, cz)) continue;
            BlockState state = probe.stateAt(cx, cy, cz);
            if (!probe.isWall(state, cx, cy, cz)) continue;
            if (!probe.isManagedAt(cx, cy, cz)) continue;
            if (!probe.isWaterAt(cx + waterDir[0], cy + waterDir[1], cz + waterDir[2])) continue;
            return k;
        }
        return Integer.MIN_VALUE;
    }

    private static int[][] perpendicular(int[] d) {
        if (d[0] != 0) return new int[][]{{0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        if (d[2] != 0) return new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        return new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    }

    private static int[][] horizontalPerpendicular(int[] d) {
        if (d[0] != 0) return new int[][]{{0, 0, 1}, {0, 0, -1}};
        return new int[][]{{1, 0, 0}, {-1, 0, 0}};
    }

    // ------------------------------------------------------------- consequences

    private record Candidate(long packed, double stress) {
    }

    /** Burst the worst-stressed blocks first, so a dam gives way at the bottom where it should. */
    private void resolve(ServerLevel level, List<Candidate> stressed) {
        stressed.sort((a, b) -> Double.compare(b.stress(), a.stress()));

        double weepAt = Config.HYDRO_WEEP_THRESHOLD.get();
        int failures = Config.HYDRO_FAILURES_PER_SWEEP.get();
        boolean effects = Config.HYDRO_EFFECTS.get();
        boolean mayBreak = Config.ENABLE_COLLAPSE.get() && Config.HYDRO_ENABLE_BREAK.get();

        for (Candidate c : stressed) {
            BlockPos pos = new BlockPos(BlockPos.getX(c.packed()), BlockPos.getY(c.packed()), BlockPos.getZ(c.packed()));
            if (c.stress() >= 1.0 && failures > 0 && mayBreak) {
                failures--;
                burst(level, pos, effects);
            } else if (c.stress() >= weepAt) {
                byte tier = (byte) Math.min(3, (int) (c.stress() / weepAt));
                if (tiers.get(c.packed()) != tier) {
                    tiers.put(c.packed(), tier);
                    if (effects) weep(level, pos);
                }
            } else {
                tiers.remove(c.packed());
            }
        }
    }

    /** Cosmetics are optional; if a particle or sound is unavailable, drop them rather than crash. */
    private static boolean effectsBroken = false;

    /** A block gives way: it is washed out rather than toppling, and the water follows it in. */
    private static void burst(ServerLevel level, BlockPos pos, boolean effects) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        if (effects && !effectsBroken) try {
            SoundType sound = state.getSoundType();
            level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS,
                    Math.max(0.5F, sound.getVolume()), sound.getPitch() * 0.6F);
            level.sendParticles(ParticleTypes.SPLASH,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 24, 0.4, 0.4, 0.4, 0.35);
        } catch (Throwable t) {
            noteEffectsBroken(t);
        }
        StructuralData.setManaged(level, pos, false);
        level.destroyBlock(pos, true);
        // Let the span system re-check what this was holding up.
        StructuralManager.enqueueChange(level, pos.asLong());
    }

    /** Under load but holding: seeping water, so a dam tells you before it goes. */
    private static void weep(ServerLevel level, BlockPos pos) {
        if (effectsBroken) return;
        try {
            level.sendParticles(ParticleTypes.DRIPPING_WATER,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.35, 0.35, 0.35, 0.0);
        } catch (Throwable t) {
            noteEffectsBroken(t);
        }
    }

    private static void noteEffectsBroken(Throwable t) {
        effectsBroken = true;
        StructuralIntegrityMod.LOGGER.warn(
                "structint: water pressure effects unavailable, continuing without them", t);
    }
}
