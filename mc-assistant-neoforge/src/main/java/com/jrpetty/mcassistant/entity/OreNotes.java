package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [mines] The ore the folk have seen and not yet taken, and the ground they have swept, kept with the world.
 *
 * <p>A miner going somewhere else (up its stairs with a full pack, past a vein in a cave roof it could not get at)
 * notes the ore it sees, and comes back for it: whoever next works within sight of a note takes it (MineGoal). Each
 * block of ground (a sixteen-block section) keeps how much ore has been taken from it and when it was last swept, so
 * the town knows a stretch is worked out (sweptOut) and when to move on.
 */
public final class OreNotes extends SavedData {

    private static final String ID = "mc_assistant_ore_notes";
    /** The most notes a world keeps; the oldest let go first. */
    static final int MOST = 8192;

    /** Each noted ore, by place, and when it was seen. */
    private final LinkedHashMap<Long, Long> notes = new LinkedHashMap<>();
    /** The notes in each chunk, so a look near a spot reads a few. */
    private final HashMap<Long, Set<Long>> byChunk = new HashMap<>();
    /** Each swept section: ore taken from it, and when it was last swept. */
    private final HashMap<Long, long[]> swept = new HashMap<>();

    private OreNotes() {}

    @Nullable private static OreNotes cached;
    @Nullable private static ServerLevel cachedFor;

    static OreNotes of(ServerLevel level) {
        if (cachedFor == level && cached != null) return cached;
        OreNotes n = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(OreNotes::new, OreNotes::load, null), ID);
        cached = n;
        cachedFor = level;
        return n;
    }

    /** Ore seen here and left for now: on the books for whoever next works within sight of it. */
    public static void noted(ServerLevel level, BlockPos pos) {
        OreNotes n = of(level);
        long p = pos.asLong();
        if (n.notes.containsKey(p)) return;
        n.notes.put(p, level.getGameTime());
        n.byChunk.computeIfAbsent(chunkOf(p), k -> new HashSet<>()).add(p);
        while (n.notes.size() > MOST) n.drop(n.notes.keySet().iterator().next());
        n.setDirty();
    }

    /** Is ore noted here? */
    public static boolean isNoted(ServerLevel level, BlockPos pos) {
        return of(level).notes.containsKey(pos.asLong());
    }

    /** Ore taken here: off the books, and counted to its stretch of ground as swept. */
    public static void taken(ServerLevel level, BlockPos pos) {
        OreNotes n = of(level);
        n.drop(pos.asLong());
        long[] s = n.swept.computeIfAbsent(SectionPos.asLong(pos), k -> new long[2]);
        s[0]++;
        s[1] = level.getGameTime();
        n.setDirty();
    }

    /** A stretch of ground worked over just now (a miner's look round it found nothing more to take). */
    public static void sweptHere(ServerLevel level, BlockPos pos) {
        OreNotes n = of(level);
        long[] s = n.swept.computeIfAbsent(SectionPos.asLong(pos), k -> new long[2]);
        s[1] = level.getGameTime();
        n.setDirty();
    }

    /** The noted ore within so many blocks of a spot still there (notes of ore since gone are let go). */
    public static List<BlockPos> near(ServerLevel level, BlockPos at, int within) {
        OreNotes n = of(level);
        List<BlockPos> out = new ArrayList<>();
        if (n.notes.isEmpty()) return out;
        long most = (long) within * within;
        List<Long> gone = new ArrayList<>();
        for (int cx = (at.getX() - within) >> 4; cx <= (at.getX() + within) >> 4; cx++) {
            for (int cz = (at.getZ() - within) >> 4; cz <= (at.getZ() + within) >> 4; cz++) {
                Set<Long> in = n.byChunk.get(ChunkPos.asLong(cx, cz));
                if (in == null) continue;
                for (long p : in) {
                    BlockPos pos = BlockPos.of(p);
                    if (pos.distSqr(at) > most || !level.isLoaded(pos)) continue;
                    if (OreSight.isOre(level.getBlockState(pos))) out.add(pos);
                    else gone.add(p);
                }
            }
        }
        for (long p : gone) n.drop(p);
        return out;
    }

    /**
     * Is the ground round this spot worked out: swept at least once, and no ore noted there that is still standing?
     * (A stretch nobody has swept is not known to be worked out.)
     */
    public static boolean sweptOut(ServerLevel level, BlockPos at, int within) {
        OreNotes n = of(level);
        if (!n.swept.containsKey(SectionPos.asLong(at))) return false;
        return near(level, at, within).isEmpty();
    }

    /** Ore taken from the section this spot is in, all told. */
    public static long takenAround(ServerLevel level, BlockPos at) {
        long[] s = of(level).swept.get(SectionPos.asLong(at));
        return s == null ? 0 : s[0];
    }

    private void drop(long p) {
        if (notes.remove(p) == null) return;
        Set<Long> in = byChunk.get(chunkOf(p));
        if (in != null) {
            in.remove(p);
            if (in.isEmpty()) byChunk.remove(chunkOf(p));
        }
        setDirty();
    }

    private static long chunkOf(long pos) {
        return ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    // ------------------------------------------------------------------ kept with the world

    public static OreNotes load(CompoundTag tag, HolderLookup.Provider registries) {
        OreNotes n = new OreNotes();
        long[] at = tag.getLongArray("Notes"), when = tag.getLongArray("Seen");
        for (int i = 0; i < at.length; i++) {
            n.notes.put(at[i], i < when.length ? when[i] : 0L);
            n.byChunk.computeIfAbsent(chunkOf(at[i]), k -> new HashSet<>()).add(at[i]);
        }
        long[] sec = tag.getLongArray("Swept"), count = tag.getLongArray("Taken"), last = tag.getLongArray("Last");
        for (int i = 0; i < sec.length; i++) {
            n.swept.put(sec[i], new long[]{ i < count.length ? count[i] : 0L, i < last.length ? last[i] : 0L });
        }
        return n;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] at = new long[notes.size()], when = new long[notes.size()];
        int i = 0;
        for (Map.Entry<Long, Long> e : notes.entrySet()) { at[i] = e.getKey(); when[i++] = e.getValue(); }
        tag.putLongArray("Notes", at);
        tag.putLongArray("Seen", when);
        long[] sec = new long[swept.size()], count = new long[swept.size()], last = new long[swept.size()];
        i = 0;
        for (Map.Entry<Long, long[]> e : swept.entrySet()) { sec[i] = e.getKey(); count[i] = e.getValue()[0]; last[i++] = e.getValue()[1]; }
        tag.putLongArray("Swept", sec);
        tag.putLongArray("Taken", count);
        tag.putLongArray("Last", last);
        return tag;
    }

    public static void resetForTests(ServerLevel level) {
        OreNotes n = of(level);
        n.notes.clear();
        n.byChunk.clear();
        n.swept.clear();
        n.setDirty();
    }
}
