package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [workitems] Where the work items stand, kept with the world: every pit prop (its lower half), by the chunk it is in, so
 * a fall of gravel is answered by a look at a handful of props and not a search of the rock round it; and every window
 * box, with the day it was last watered. The blocks keep it up to date themselves as they are set and taken down
 * (PitPropBlock, WindowBoxBlock), whoever set them.
 */
public final class WorkSites extends SavedData {

    private static final String ID = "mc_assistant_work_sites";

    /** The props, by chunk. */
    private final Map<Long, Set<Long>> props = new HashMap<>();
    /** The window boxes, each with the day it was last watered (or hung). */
    private final Map<Long, Long> boxes = new HashMap<>();

    private WorkSites() {}

    @Nullable private static WorkSites cached;
    @Nullable private static ServerLevel cachedFor;

    static WorkSites of(ServerLevel level) {
        if (cachedFor == level && cached != null) return cached;
        WorkSites s = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(WorkSites::new, WorkSites::load, null), ID);
        cached = s;
        cachedFor = level;
        return s;
    }

    // ------------------------------------------------------------------ the props

    public static void propSet(ServerLevel level, BlockPos lower) {
        WorkSites s = of(level);
        if (s.props.computeIfAbsent(ChunkPos.asLong(lower), k -> new HashSet<>()).add(lower.asLong())) s.setDirty();
    }

    public static void propGone(ServerLevel level, BlockPos lower) {
        WorkSites s = of(level);
        Set<Long> in = s.props.get(ChunkPos.asLong(lower));
        if (in != null && in.remove(lower.asLong())) {
            if (in.isEmpty()) s.props.remove(ChunkPos.asLong(lower));
            s.setDirty();
        }
    }

    /** The props (their lower halves) within so many blocks across of here (any height), by the chunks round it. */
    public static List<BlockPos> propsNear(ServerLevel level, BlockPos at, int across) {
        WorkSites s = of(level);
        List<BlockPos> out = new ArrayList<>();
        if (s.props.isEmpty()) return out;
        int c0x = (at.getX() - across) >> 4, c1x = (at.getX() + across) >> 4, c0z = (at.getZ() - across) >> 4, c1z = (at.getZ() + across) >> 4;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                Set<Long> in = s.props.get(ChunkPos.asLong(cx, cz));
                if (in == null) continue;
                for (long l : in) {
                    BlockPos p = BlockPos.of(l);
                    if (Math.abs(p.getX() - at.getX()) <= across && Math.abs(p.getZ() - at.getZ()) <= across) out.add(p);
                }
            }
        }
        return out;
    }

    /** How many props stand in all (in this level). */
    public static int propCount(ServerLevel level) {
        int n = 0;
        for (Set<Long> in : of(level).props.values()) n += in.size();
        return n;
    }

    // ------------------------------------------------------------------ the window boxes

    public static void boxHung(ServerLevel level, BlockPos pos) {
        WorkSites s = of(level);
        s.boxes.putIfAbsent(pos.asLong(), level.getDayTime() / 24000L);
        s.setDirty();
    }

    public static void boxGone(ServerLevel level, BlockPos pos) {
        WorkSites s = of(level);
        if (s.boxes.remove(pos.asLong()) != null) s.setDirty();
    }

    /** The day this box was last watered, or -1 for no box known here. */
    public static long wateredOn(ServerLevel level, BlockPos pos) {
        Long d = of(level).boxes.get(pos.asLong());
        return d == null ? -1 : d;
    }

    public static void water(ServerLevel level, BlockPos pos, long day) {
        WorkSites s = of(level);
        s.boxes.put(pos.asLong(), day);
        s.setDirty();
    }

    /** Every window box known, within so many blocks across of here. */
    public static List<BlockPos> boxesNear(ServerLevel level, BlockPos at, int across) {
        List<BlockPos> out = new ArrayList<>();
        for (long l : of(level).boxes.keySet()) {
            BlockPos p = BlockPos.of(l);
            if (Math.abs(p.getX() - at.getX()) <= across && Math.abs(p.getZ() - at.getZ()) <= across) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ kept with the world

    public static WorkSites load(CompoundTag tag, HolderLookup.Provider registries) {
        WorkSites s = new WorkSites();
        for (long l : tag.getLongArray("Props")) s.props.computeIfAbsent(ChunkPos.asLong(BlockPos.of(l)), k -> new HashSet<>()).add(l);
        for (Tag t : tag.getList("Boxes", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) t;
            s.boxes.put(b.getLong("At"), b.getLong("Watered"));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Long> all = new ArrayList<>();
        for (Set<Long> in : props.values()) all.addAll(in);
        long[] arr = new long[all.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = all.get(i);
        tag.putLongArray("Props", arr);
        ListTag list = new ListTag();
        for (Map.Entry<Long, Long> e : boxes.entrySet()) {
            CompoundTag b = new CompoundTag();
            b.putLong("At", e.getKey());
            b.putLong("Watered", e.getValue());
            list.add(b);
        }
        tag.put("Boxes", list);
        return tag;
    }

    /** Tests: forget every prop and box (the tests share one world). */
    public static void resetForTests(ServerLevel level) {
        WorkSites s = of(level);
        s.props.clear();
        s.boxes.clear();
        s.setDirty();
    }
}
