package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [emerald] The villages of the game's own villagers, as the folk know of them: where each lies and how far its ground
 * runs. The folk and the villagers are two peoples now, kept apart (TwoPeoples), and keeping them apart starts with
 * knowing where the villagers' ground is.
 *
 * <p><b>How a village of villagers is known.</b> Two ways, and either will do:
 * <ul>
 * <li><b>By what the world built.</b> A village the world generated is a structure, and its bounds are the structure's
 *     own: every house, field and path the generator laid. Looked up the way the game's own locator looks for one,
 *     placement by placement, round a spot that matters (a town being founded, a town's ground, a trader on the road),
 *     once a region per session; and read off the chunk a folk is standing in, which costs nothing.</li>
 * <li><b>By its people.</b> Villagers meet at a bell. A bell that villagers have taken for their meeting place, outside
 *     every town of ours, is a village of villagers wherever it stands (one a player built round a bell is as much a
 *     village as one the world built); its ground runs as far as their homes and their work, and a little way past.</li>
 * </ul>
 *
 * <p><b>What it is for.</b> No town is founded within {@link #CLEAR} blocks of one; no lot, field, wood, mine, wall or
 * levelled ground of a town comes within {@link #MARGIN} of its edge; no folk takes a bed, a chest or a bell inside it;
 * and the emerald trader (EmeraldTrader) walks to them to trade. In a world that still takes villagers over
 * (replaceVillagers), none of this holds: their villages become towns, as they always did.
 */
public final class VanillaVillages {

    private VanillaVillages() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** No lot, field, wood, mine, wall or levelled ground within this many blocks of a village of villagers' edge. */
    public static final int MARGIN = 32;
    /** No town's heart is founded nearer than this to a village of villagers' edge. */
    public static final int CLEAR = 128;
    /** A village known only by its people's bell is let go if nobody has seen them about it in this long (ticks). */
    static final long FORGET_BELL = 24000L * 10;
    /** How far round a bell its villagers' homes and work count as its ground, at most. */
    static final int BELL_GROUND = 64;

    /** A village of villagers: its key, its world, its middle, its bounds, its kind ("plains", "desert"), whether the
     *  world built it (else it is known by its bell), and when it was last seen to be there. */
    public record Known(String key, ResourceKey<Level> dim, BlockPos centre, int x0, int z0, int x1, int z1, String kind,
                        boolean built, long seen) {

        /** Is this column inside it, or within so many blocks of its edge? */
        public boolean within(int x, int z, int margin) {
            return x >= x0 - margin && x <= x1 + margin && z >= z0 - margin && z <= z1 + margin;
        }

        /** Does this box (its corners) come within so many blocks of it? */
        public boolean meets(int ax0, int az0, int ax1, int az1, int margin) {
            return Math.max(ax0, ax1) >= x0 - margin && Math.min(ax0, ax1) <= x1 + margin
                && Math.max(az0, az1) >= z0 - margin && Math.min(az0, az1) <= z1 + margin;
        }

        /** How far this column is from its edge (nought inside it). */
        public int edge(int x, int z) {
            int dx = Math.max(0, Math.max(x0 - x, x - x1)), dz = Math.max(0, Math.max(z0 - z, z - z1));
            return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        }

        /** "a plains village", "a village" (a bell's). */
        public String words() {
            return kind.isEmpty() ? "a village of villagers" : JobMarket.a(kind) + " village";
        }

        /** Its width and depth, in blocks. */
        public int across() { return x1 - x0 + 1; }
        public int deep() { return z1 - z0 + 1; }
    }

    /** Every village of villagers known this session, by key. */
    private static final Map<String, Known> KNOWN = new ConcurrentHashMap<>();
    /** The placement regions whose villages have been looked up, so each is looked up once a session. */
    private static final Set<String> LOOKED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        KNOWN.clear();
        LOOKED.clear();
    }

    /** Are the two peoples kept apart? Always, unless the old takeover is on: then the villagers' villages become towns. */
    public static boolean apart() {
        return !AssistantConfig.replaceVillagers();
    }

    // ------------------------------------------------------------------ asking

    /** Every village of villagers known in this world. */
    public static List<Known> all(Level level) {
        List<Known> out = new ArrayList<>();
        for (Known k : KNOWN.values()) if (k.dim().equals(level.dimension())) out.add(k);
        return out;
    }

    /** The village of villagers this column is inside (or within so many blocks of), or null. Never one while the
     *  takeover is on. */
    @Nullable
    public static Known at(Level level, int x, int z, int margin) {
        if (!apart()) return null;
        for (Known k : KNOWN.values()) {
            if (k.dim().equals(level.dimension()) && k.within(x, z, margin)) return k;
        }
        return null;
    }

    /** Is this column inside a village of villagers, or within so many blocks of one? */
    public static boolean within(Level level, int x, int z, int margin) {
        return at(level, x, z, margin) != null;
    }

    /** Is this spot inside the ground the world built for a village of villagers (its own houses and fields)? */
    public static boolean builtAt(Level level, BlockPos p) {
        if (!apart()) return false;
        for (Known k : KNOWN.values()) {
            if (k.built() && k.dim().equals(level.dimension()) && k.within(p.getX(), p.getZ(), 0)) return true;
        }
        return false;
    }

    /** Does this box come within so many blocks of a village of villagers? */
    public static boolean meets(Level level, int x0, int z0, int x1, int z1, int margin) {
        if (!apart()) return false;
        for (Known k : KNOWN.values()) {
            if (k.dim().equals(level.dimension()) && k.meets(x0, z0, x1, z1, margin)) return true;
        }
        return false;
    }

    /** The village of villagers nearest this spot (by its edge), within so many blocks, or null. */
    @Nullable
    public static Known nearest(Level level, BlockPos from, int range) {
        Known best = null;
        int bestD = Integer.MAX_VALUE;
        for (Known k : KNOWN.values()) {
            if (!k.dim().equals(level.dimension())) continue;
            int d = k.edge(from.getX(), from.getZ());
            if (d <= range && d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    /** By its key. */
    @Nullable
    public static Known get(String key) {
        return KNOWN.get(key);
    }

    /**
     * May a town's heart be founded here? Not within {@link #CLEAR} of a village of villagers: the world's villages
     * round it are looked up first (they may never have been seen yet). Null if it may; else the village in the way.
     */
    @Nullable
    public static Known inTheWayOfFounding(ServerLevel level, BlockPos heart) {
        if (!apart()) return null;
        lookAround(level, heart, CLEAR + 192);
        return nearest(level, heart, CLEAR);
    }

    /** How far a founding's worked ground may reach from this heart before it comes within the margin of a village of
     *  villagers: the nearest one's edge, less the margin. Integer.MAX_VALUE if there is none near. */
    public static int roomFrom(Level level, BlockPos heart) {
        if (!apart()) return Integer.MAX_VALUE;
        int room = Integer.MAX_VALUE;
        for (Known k : KNOWN.values()) {
            if (!k.dim().equals(level.dimension())) continue;
            int d = k.edge(heart.getX(), heart.getZ()) - MARGIN;
            if (d < room) room = d;
        }
        return room;
    }

    // ------------------------------------------------------------------ finding them: what the world built

    /**
     * Look up every village the world has put, or will put, within so many blocks of here: the game's own villages'
     * placements, region by region (each region once a session), each candidate checked for a start the way the
     * game's locator checks one, and a start found read for its bounds. Loads no more of a chunk than its structure
     * starts. Nothing in a world with no structures (the test worlds).
     */
    public static int lookAround(ServerLevel level, BlockPos at, int blocks) {
        if (!apart()) return 0;
        int found = 0;
        try {
            if (!level.structureManager().shouldGenerateStructures()) return 0;
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var tag = registry.getTag(StructureTags.VILLAGE);
            if (tag.isEmpty()) return 0;
            HolderSet.Named<Structure> villages = tag.get();
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            int cx0 = (at.getX() - blocks) >> 4, cx1 = (at.getX() + blocks) >> 4;
            int cz0 = (at.getZ() - blocks) >> 4, cz1 = (at.getZ() + blocks) >> 4;
            for (Holder<Structure> h : villages) {
                for (StructurePlacement pl : state.getPlacementsForStructure(h)) {
                    if (!(pl instanceof RandomSpreadStructurePlacement rs) || rs.spacing() <= 0) continue;
                    int sp = rs.spacing();
                    for (int rx = Math.floorDiv(cx0, sp) - 1; rx <= Math.floorDiv(cx1, sp) + 1; rx++) {
                        for (int rz = Math.floorDiv(cz0, sp) - 1; rz <= Math.floorDiv(cz1, sp) + 1; rz++) {
                            ChunkPos c = rs.getPotentialStructureChunk(state.getLevelSeed(), rx, rz);
                            // Its start may lie a region off and its houses still reach here: up to eight chunks either way.
                            if (c.x < cx0 - 8 || c.x > cx1 + 8 || c.z < cz0 - 8 || c.z > cz1 + 8) continue;
                            String look = level.dimension().location() + "|" + registry.getKey(h.value()) + "|" + c.x + "|" + c.z;
                            if (!LOOKED.add(look)) continue;
                            StructureCheckResult r = level.structureManager().checkStructurePresence(c, h.value(), pl, false);
                            if (r == StructureCheckResult.START_NOT_PRESENT) continue;
                            ChunkAccess chunk = level.getChunk(c.x, c.z, ChunkStatus.STRUCTURE_STARTS);
                            StructureStart start = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk), h.value(), chunk);
                            if (start == null || !start.isValid()) continue;
                            if (record(level, start, registry.getKey(h.value())) != null) found++;
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            // A look round is a courtesy: a world whose generator will not say leaves the villagers' villages to be
            // known by their bells, as they are anyway.
            LOG.debug("[MCA-PEOPLES] looking for villages round {} failed: {}", at.toShortString(), ex.toString());
        }
        return found;
    }

    /** The village the world built that this spot (in a chunk that is loaded) is part of, noted; null if none. Cheap:
     *  the chunk's own record of what reaches into it. */
    @Nullable
    public static Known sawFrom(ServerLevel level, BlockPos at) {
        if (!apart() || !level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) return null;
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : level.structureManager().getAllStructuresAt(at).entrySet()) {
                Holder<Structure> h = registry.wrapAsHolder(e.getKey());
                if (!h.is(StructureTags.VILLAGE)) continue;
                List<StructureStart> starts = new ArrayList<>();
                level.structureManager().fillStartsForStructure(e.getKey(), e.getValue(), starts::add);
                for (StructureStart s : starts) {
                    if (s.isValid()) return record(level, s, registry.getKey(e.getKey()));
                }
            }
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-PEOPLES] structure look at {} failed: {}", at.toShortString(), ex.toString());
        }
        return null;
    }

    /** A start the world built, written down. */
    @Nullable
    private static Known record(ServerLevel level, StructureStart start, @Nullable ResourceLocation id) {
        BoundingBox b = start.getBoundingBox();
        ChunkPos c = start.getChunkPos();
        String key = level.dimension().location() + "@" + c.x + "," + c.z;
        Known had = KNOWN.get(key);
        if (had != null) return had;
        String path = id == null ? "" : id.getPath();
        String kind = path.startsWith("village_") ? path.substring("village_".length()).replace('_', ' ') : "";
        BlockPos centre = new BlockPos((b.minX() + b.maxX()) / 2, b.minY() + 1, (b.minZ() + b.maxZ()) / 2);
        if (level.hasChunk(centre.getX() >> 4, centre.getZ() >> 4)) centre = Scouts.surface(level, centre);
        Known k = new Known(key, level.dimension(), centre, b.minX(), b.minZ(), b.maxX(), b.maxZ(), kind, true, level.getGameTime());
        // A village of villagers known by its bell that turns out to be the world's own: the world's bounds win.
        KNOWN.values().removeIf(o -> !o.built() && o.dim().equals(k.dim()) && k.within(o.centre().getX(), o.centre().getZ(), 8));
        KNOWN.put(key, k);
        LOG.info("[MCA-PEOPLES] {} village of villagers at {} ({}x{})", kind.isEmpty() ? "a" : kind, centre.toShortString(), k.across(), k.deep());
        return k;
    }

    // ------------------------------------------------------------------ finding them: their people

    /**
     * Villagers meet at this bell (TwoPeoples' sweep): a village of villagers stands round it, its ground as far as
     * their homes and their work (within {@link #BELL_GROUND}), and sixteen blocks past. Kept fresh while they are seen.
     * Null where the bell is inside the ground the world built for a village (that one is already known).
     */
    @Nullable
    static Known heardAt(ServerLevel level, BlockPos bell, List<BlockPos> ground) {
        if (!apart()) return null;
        for (Known k : KNOWN.values()) {
            if (k.built() && k.dim().equals(level.dimension()) && k.within(bell.getX(), bell.getZ(), 8)) return k;
        }
        int x0 = bell.getX() - 16, x1 = bell.getX() + 16, z0 = bell.getZ() - 16, z1 = bell.getZ() + 16;
        for (BlockPos p : ground) {
            if (Math.abs(p.getX() - bell.getX()) > BELL_GROUND || Math.abs(p.getZ() - bell.getZ()) > BELL_GROUND) continue;
            x0 = Math.min(x0, p.getX() - 16);
            x1 = Math.max(x1, p.getX() + 16);
            z0 = Math.min(z0, p.getZ() - 16);
            z1 = Math.max(z1, p.getZ() + 16);
        }
        String key = level.dimension().location() + "#bell@" + bell.getX() + "," + bell.getY() + "," + bell.getZ();
        Known had = KNOWN.get(key);
        if (had != null) {
            x0 = Math.min(x0, had.x0());
            x1 = Math.max(x1, had.x1());
            z0 = Math.min(z0, had.z0());
            z1 = Math.max(z1, had.z1());
        }
        Known k = new Known(key, level.dimension(), bell.immutable(), x0, z0, x1, z1, "", false, level.getGameTime());
        KNOWN.put(key, k);
        if (had == null) LOG.info("[MCA-PEOPLES] a village of villagers round the bell at {} ({}x{})", bell.toShortString(), k.across(), k.deep());
        return k;
    }

    /** Villages known only by a bell nobody has been seen at for a long while, or whose bell is gone, are let go. */
    static void forgetStale(ServerLevel level) {
        long now = level.getGameTime();
        KNOWN.values().removeIf(k -> !k.built() && k.dim().equals(level.dimension())
            && (now - k.seen() > FORGET_BELL || now < k.seen()
                || level.isLoaded(k.centre()) && !level.getBlockState(k.centre()).is(net.minecraft.world.level.block.Blocks.BELL)));
    }

    // ------------------------------------------------------------------ for the tests and the books

    /** Tests (and the stage): a village of villagers known round this bell, with these bounds, as its people would show it. */
    public static Known recordForTests(ServerLevel level, BlockPos bell, int halfAcross) {
        String key = level.dimension().location() + "#bell@" + bell.getX() + "," + bell.getY() + "," + bell.getZ();
        Known k = new Known(key, level.dimension(), bell.immutable(), bell.getX() - halfAcross, bell.getZ() - halfAcross,
            bell.getX() + halfAcross, bell.getZ() + halfAcross, "", false, level.getGameTime());
        KNOWN.put(key, k);
        return k;
    }

    /** One line, for /village and the log. */
    public static String describe(Level level, BlockPos from) {
        List<Known> all = all(level);
        if (all.isEmpty()) return "no village of villagers known";
        all.sort((a, b) -> Integer.compare(a.edge(from.getX(), from.getZ()), b.edge(from.getX(), from.getZ())));
        StringBuilder sb = new StringBuilder();
        for (Known k : all.subList(0, Math.min(5, all.size()))) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k.words()).append(" at ").append(k.centre().getX()).append(", ").append(k.centre().getZ())
                .append(" (").append(k.across()).append("x").append(k.deep()).append(", ").append(k.built() ? "built" : "by its bell")
                .append(", ").append(k.edge(from.getX(), from.getZ())).append(" off)");
        }
        return sb.toString();
    }
}
