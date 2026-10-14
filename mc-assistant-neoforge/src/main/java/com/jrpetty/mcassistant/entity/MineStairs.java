package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stairs down into the mines, kept with the world: every staircase a miner has cut, step by
 * step from its head, and the block each step stands on.
 *
 * <p>A miner only ever knew the steps of the run it was on. Run after run, and miner after miner, the
 * steps of the others were just stone: a gallery or a vein cut under them, a hand sent for stone took
 * the nearest there was (the steps at the head of the stairs, first of all), and the next miner down
 * came back up to a two-block drop it could not climb, and stood at the bottom of its own mine for
 * good. Now the floor of every step is known, and nobody cutting stone takes one (GatherGoal, MineGoal);
 * a miner on its way up mends whatever step is missing all the same (MineGoal.mend); and a folk lost
 * underground with no way it can walk takes the nearest stairs up, or cuts its own (MineGoal, "out").
 */
public final class MineStairs extends SavedData {

    private static final String ID = "mc_assistant_mine_stairs";
    /** Steps a staircase keeps, head first (a mine to the bottom of the world spirals a long way). */
    public static final int MOST_STEPS = 1024;
    /** Staircases a world keeps; the oldest let go first. */
    public static final int MOST_STAIRS = 400;

    /** Each staircase by its key (the plot it serves, or where it came out), head first. */
    private final LinkedHashMap<Long, List<Long>> stairs = new LinkedHashMap<>();
    /** How many staircases stand a step on each block: the floors nobody may break. */
    private final HashMap<Long, Integer> floors = new HashMap<>();
    /** [mines] When each staircase was last walked, up or down (game time): the ways nobody uses are let go. */
    private final HashMap<Long, Long> used = new HashMap<>();

    /** [mines] The kinds of way a key names, by its y: a mine's main stairs (a plot's), stairs a folk cut to get out,
     *  footholds kept as a way down. Only the main stairs are kept for good. */
    public enum Kind { MAIN, OUT, KEPT }

    /** [mines] A way out or a kept way down nobody has walked for this long (five days) is let go. */
    public static final long UNUSED = 5L * 24000L;

    private MineStairs() {}

    @Nullable private static MineStairs cached;
    @Nullable private static ServerLevel cachedFor;

    static MineStairs of(ServerLevel level) {
        if (cachedFor == level && cached != null) return cached;
        MineStairs s = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MineStairs::new, MineStairs::load, null), ID);
        cached = s;
        cachedFor = level;
        return s;
    }

    /** The key of a plot's stairs: its middle, X and Z. */
    public static long plotKey(BlockPos centre) {
        return BlockPos.asLong(centre.getX(), 0, centre.getZ());
    }

    // ------------------------------------------------------------------ steps

    /**
     * The index-th step (head first) of the staircase at this key is here. The same stairs walked down
     * again leave it as it was; a run whose stairs part from the old ones at some step keeps its own
     * from there on, and the old ones below that step are let go.
     */
    public static void step(ServerLevel level, long key, int index, BlockPos feet) {
        MineStairs m = of(level);
        List<Long> list = m.stairs.computeIfAbsent(key, k -> new ArrayList<>());
        long at = feet.asLong();
        if (index < list.size()) {
            if (list.get(index) == at) return;
            while (list.size() > index) m.unfloor(list.remove(list.size() - 1));
        }
        if (list.size() >= MOST_STEPS) return;
        list.add(at);
        m.floor(at);
        m.trim();
        m.setDirty();
    }

    /** A whole staircase, head first, under this key (a way out a folk cut for itself). */
    public static void record(ServerLevel level, long key, List<BlockPos> headFirst) {
        MineStairs m = of(level);
        List<Long> old = m.stairs.remove(key);
        if (old != null) for (long s : old) m.unfloor(s);
        List<Long> list = new ArrayList<>();
        for (BlockPos p : headFirst) {
            if (list.size() >= MOST_STEPS) break;
            list.add(p.asLong());
            m.floor(p.asLong());
        }
        m.stairs.put(key, list);
        m.used.put(key, level.getGameTime());   // [mines]
        m.trim();
        m.setDirty();
    }

    /** Is this block the floor of a step on somebody's stairs? Nobody cutting stone takes it. */
    public static boolean isFloor(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return false;
        MineStairs m = of(server);
        return !m.floors.isEmpty() && m.floors.containsKey(pos.asLong());
    }

    /** A staircase near here (its key), head first to the step of it nearest this spot. */
    public record Near(long key, List<BlockPos> steps, int index, double distance) {}

    /** The staircase with a step nearest this spot, within so many blocks; null when there is none. */
    @Nullable
    public static Near nearest(ServerLevel level, BlockPos at, int within) {
        List<Near> all = near(level, at, within, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * [mines] The known ways up near this spot, nearest first, one to a staircase and at most so many: each staircase's
     * step nearest the spot, within so many blocks. A folk going up tries them in turn (path-checked) before it cuts
     * a way of its own.
     */
    public static List<Near> near(ServerLevel level, BlockPos at, int within, int most) {
        MineStairs m = of(level);
        double limit = (double) within * within;
        List<Near> out = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : m.stairs.entrySet()) {
            List<Long> list = e.getValue();
            double best = limit;
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                double d = BlockPos.of(list.get(i)).distSqr(at);
                if (d <= best) { best = d; index = i; }
            }
            if (index < 0) continue;
            List<BlockPos> steps = new ArrayList<>(index + 1);
            for (int i = 0; i <= index; i++) steps.add(BlockPos.of(list.get(i)));
            out.add(new Near(e.getKey(), steps, index, Math.sqrt(best)));
        }
        out.sort(java.util.Comparator.comparingDouble(Near::distance));
        return out.size() > most ? new ArrayList<>(out.subList(0, most)) : out;
    }

    /** [mines] The whole staircase at this key, head first (empty when there is none). */
    public static List<BlockPos> steps(ServerLevel level, long key) {
        List<Long> list = of(level).stairs.get(key);
        List<BlockPos> out = new ArrayList<>(list == null ? 0 : list.size());
        if (list != null) for (long s : list) out.add(BlockPos.of(s));
        return out;
    }

    /** [mines] The staircase at this key was walked just now: kept on. */
    public static void used(ServerLevel level, long key) {
        MineStairs m = of(level);
        if (!m.stairs.containsKey(key)) return;
        m.used.put(key, level.getGameTime());
        m.setDirty();
    }

    /** [mines] What kind of way this key names. */
    public static Kind kindOf(long key) {
        int y = BlockPos.getY(key);
        return y == 1 ? Kind.OUT : y == 2 ? Kind.KEPT : Kind.MAIN;
    }

    /** [mines] The key a way out cut by a folk is kept under: where it came out. */
    public static long outKey(BlockPos top) {
        return BlockPos.asLong(top.getX(), 1, top.getZ());
    }

    /** [mines] Footholds kept as a way down (Footholds.keep), top first, booked as a route under where they stand. */
    static void keptRoute(ServerLevel level, List<BlockPos> topFirst) {
        if (topFirst.isEmpty()) return;
        BlockPos top = topFirst.get(0);
        long key = BlockPos.asLong(top.getX(), 2, top.getZ());
        record(level, key, topFirst);
    }

    /**
     * [mines] The ways nobody has walked for UNUSED let go (the town's tidy round of its routes): a way out forgotten
     * (its steps no longer spared), kept footholds handed back to be taken down. A mine's main stairs are never let go.
     */
    static void forgetUnused(ServerLevel level, long now) {
        MineStairs m = of(level);
        List<Long> gone = new ArrayList<>();
        for (Long key : m.stairs.keySet()) {
            if (kindOf(key) == Kind.MAIN) continue;
            Long at = m.used.get(key);
            if (at == null || at > now) { m.used.put(key, now); m.setDirty(); continue; }   // an older save: from now
            if (now - at > UNUSED) gone.add(key);
        }
        for (long key : gone) {
            List<Long> list = m.stairs.remove(key);
            m.used.remove(key);
            if (list == null) continue;
            for (long s : list) m.unfloor(s);
            if (kindOf(key) == Kind.KEPT) {
                List<BlockPos> steps = new ArrayList<>(list.size());
                for (long s : list) steps.add(BlockPos.of(s));
                Footholds.unkeep(level, steps);
            }
            m.setDirty();
        }
    }

    /** [mine-safety] The head of the staircase at this key (its first step, at the top), or null. */
    @Nullable
    public static BlockPos head(ServerLevel level, long key) {
        List<Long> list = of(level).stairs.get(key);
        return list == null || list.isEmpty() ? null : BlockPos.of(list.get(0));
    }

    /** [mine-safety] The first few steps of every staircase, head first (MineSafety fences their tops). */
    public static List<List<BlockPos>> tops(ServerLevel level, int steps) {
        List<List<BlockPos>> out = new ArrayList<>();
        for (List<Long> list : of(level).stairs.values()) {
            if (list.isEmpty()) continue;
            List<BlockPos> top = new ArrayList<>(Math.min(steps, list.size()));
            for (int i = 0; i < list.size() && i < steps; i++) top.add(BlockPos.of(list.get(i)));
            out.add(top);
        }
        return out;
    }

    private void floor(long step) {
        floors.merge(BlockPos.of(step).below().asLong(), 1, Integer::sum);
    }

    private void unfloor(long step) {
        long f = BlockPos.of(step).below().asLong();
        Integer n = floors.get(f);
        if (n == null) return;
        if (n <= 1) floors.remove(f); else floors.put(f, n - 1);
    }

    private void trim() {
        while (stairs.size() > MOST_STAIRS) {
            Long oldest = stairs.keySet().iterator().next();
            for (long s : stairs.remove(oldest)) unfloor(s);
            used.remove(oldest);   // [mines]
        }
    }

    // ------------------------------------------------------------- underground

    /** Ground a folk may cut its way through to get out: rock, earth, sand and gravel, ore, and the
     *  cobblestone a miner lays itself. Never what somebody built or put there, and never bedrock. */
    public static boolean ground(BlockState s) {
        if (s.hasBlockEntity() || s.is(Blocks.BEDROCK)) return false;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE)
            || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
            || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SMOOTH_BASALT)
            || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)
            || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
            || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
            || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
            || s.is(Blocks.NETHERRACK) || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SOUL_SOIL);
    }

    /** Out in the open: nothing over this spot but sky (or leaves). */
    public static boolean open(Level level, BlockPos feet) {
        return feet.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - 1;
    }

    /** Rock and earth as the world made them: what a cave's roof is made of, and never a building's
     *  floor (cobblestone, sandstone and terracotta are what towns build with). */
    private static boolean natural(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
            || s.is(Blocks.CLAY) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
            || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
            || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.NETHERRACK);
    }

    /**
     * Underground: three or more blocks of rock or earth over its head before the open sky. A house's
     * roof is not rock, nor a market's awning, nor the floors of a block of flats; a cave, a mine or
     * a hole it fell down is.
     */
    public static boolean underground(Level level, BlockPos feet) {
        if (open(level, feet)) return false;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        int rock = 0;
        BlockPos.MutableBlockPos p = feet.mutable();
        for (int y = feet.getY() + 2; y < top && y < feet.getY() + 64; y++) {
            if (natural(level.getBlockState(p.setY(y))) && ++rock >= 3) return true;
        }
        return false;
    }

    // ---------------------------------------------------------- lost below

    /** Looks in a row each folk has been underground with no way up it could walk. */
    private static final Map<UUID, Integer> BELOW = new ConcurrentHashMap<>();
    /** Times each has been sent up since it was last out in the open. */
    private static final Map<UUID, Integer> SENT = new ConcurrentHashMap<>();

    /**
     * Once in five seconds (VillageFolkEntity.aiStep): a folk underground and not on a mine run of its
     * own, that has been down there a while and can find no way it can walk up to its town, is sent up
     * (a mine job "out": the nearest stairs, mended as it climbs, or stairs of its own). A mine run
     * that was cut short (the morning bell, a fight, its day's work called off) left its miner at the
     * bottom with every way home a walk the pathfinder could not see the end of.
     */
    public static void lookForAWayUp(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.getUUID();
        Job j = f.peekJob();
        if (j != null && j.type() == Job.Type.MINE) {                                // its own run sees it home
            BELOW.remove(id);
            // [mine-safety] Its way up first in the queue, and another goal still with its legs (a deposit to
            // stores it cannot walk to): that one lets go, and the climb begins.
            if (OUT.equals(j.arg()) && !f.running(com.jrpetty.mcassistant.entity.goal.MineGoal.class)) f.interject(null);
            return;
        }
        if (CaveDwellers.caving(f)) { BELOW.remove(id); return; }       // [caves] down there on purpose: its own marks see it home
        if (!f.isAlive() || f.isPassenger() || f.isSleeping()) return;
        BlockPos feet = f.blockPosition();
        // [mine-safety] Or deep in the town's mine with the sky over it (the open top of a pit, a gallery out
        // into a hillside): below ground all the same (MineSafety.below).
        if (!underground(level, feet) && !MineSafety.below(level, f.ownerId(), feet)) {
            BELOW.remove(id);
            SENT.remove(id);
            return;
        }
        int looks = BELOW.merge(id, 1, Integer::sum);
        if (looks < 3) return;                           // a few seconds through a cave mouth is not being lost
        BlockPos home = f.villageCentre();
        if (home != null) {
            BlockPos up = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home);
            Path way = f.getNavigation().createPath(up, 3);
            if (way != null && way.canReach()) {
                BELOW.put(id, -6);                       // it can walk up: left to it, and not looked at again for a while
                return;
            }
        }
        int sent = SENT.merge(id, 1, Integer::sum);
        if (sent > 4 && sent % 12 != 0) return;           // tried and failed: once a minute after that
        f.interject(Job.mine(feet.getY(), OUT));         // [mine-safety] now: not behind whatever goal has its legs
        f.brain("lost underground: making for the surface");
    }

    /** The mine job's word for "get out from down here". */
    public static final String OUT = "out";

    // ------------------------------------------------------------ kept with the world

    public static MineStairs load(CompoundTag tag, HolderLookup.Provider registries) {
        MineStairs m = new MineStairs();
        for (Tag t : tag.getList("Stairs", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            List<Long> list = new ArrayList<>();
            for (long s : one.getLongArray("Steps")) {
                list.add(s);
                m.floor(s);
            }
            m.stairs.put(one.getLong("Key"), list);
            if (one.contains("Used")) m.used.put(one.getLong("Key"), one.getLong("Used"));   // [mines]
        }
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<Long, List<Long>> e : stairs.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putLong("Key", e.getKey());
            long[] steps = new long[e.getValue().size()];
            for (int i = 0; i < steps.length; i++) steps[i] = e.getValue().get(i);
            one.putLongArray("Steps", steps);
            Long at = used.get(e.getKey());
            if (at != null) one.putLong("Used", at);   // [mines]
            all.add(one);
        }
        tag.put("Stairs", all);
        return tag;
    }

    public static void resetForTests(ServerLevel level) {
        MineStairs m = of(level);
        m.stairs.clear();
        m.floors.clear();
        m.used.clear();   // [mines]
        m.setDirty();
        BELOW.clear();
        SENT.clear();
    }

    /** Tests: the steps of the staircase at this key, head first. */
    public static List<BlockPos> stairsForTests(ServerLevel level, long key) {
        List<BlockPos> out = new ArrayList<>();
        List<Long> list = of(level).stairs.get(key);
        if (list != null) for (long s : list) out.add(BlockPos.of(s));
        return out;
    }

    /** Tests: every staircase's key. */
    public static List<Long> keysForTests(ServerLevel level) {
        return new ArrayList<>(of(level).stairs.keySet());
    }
}
