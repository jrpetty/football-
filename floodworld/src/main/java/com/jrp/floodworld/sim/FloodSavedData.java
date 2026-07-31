package com.jrp.floodworld.sim;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension persistent state: soil saturation, tracked flood cells and their base elevation. */
public class FloodSavedData extends SavedData {

    public static final String ID = "floodworld_flood";

    private final Long2IntOpenHashMap saturation = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap flood = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap baseY = new Long2IntOpenHashMap();

    public FloodSavedData() {
        // These defaults double as "absent" sentinels on the read paths.
        saturation.defaultReturnValue(0);
        flood.defaultReturnValue(-1);
        baseY.defaultReturnValue(Integer.MIN_VALUE);
    }

    public Long2IntOpenHashMap saturation() {
        return saturation;
    }

    public Long2IntOpenHashMap flood() {
        return flood;
    }

    public Long2IntOpenHashMap baseY() {
        return baseY;
    }

    public static SavedData.Factory<FloodSavedData> factory() {
        return new SavedData.Factory<>(FloodSavedData::new, FloodSavedData::load, null);
    }

    private static FloodSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FloodSavedData data = new FloodSavedData();
        readMap(tag, "sat", data.saturation);
        readMap(tag, "flood", data.flood);
        readMap(tag, "baseY", data.baseY);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        writeMap(tag, "sat", saturation);
        writeMap(tag, "flood", flood);
        writeMap(tag, "baseY", baseY);
        return tag;
    }

    private static void writeMap(CompoundTag tag, String key, Long2IntOpenHashMap map) {
        long[] keys = new long[map.size()];
        int[] vals = new int[map.size()];
        int i = 0;
        for (Long2IntMap.Entry e : map.long2IntEntrySet()) {
            keys[i] = e.getLongKey();
            vals[i] = e.getIntValue();
            i++;
        }
        CompoundTag sub = new CompoundTag();
        sub.putLongArray("k", keys);
        sub.putIntArray("v", vals);
        tag.put(key, sub);
    }

    private static void readMap(CompoundTag tag, String key, Long2IntOpenHashMap map) {
        map.clear();
        if (!tag.contains(key)) return;
        CompoundTag sub = tag.getCompound(key);
        long[] keys = sub.getLongArray("k");
        int[] vals = sub.getIntArray("v");
        int n = Math.min(keys.length, vals.length);
        for (int i = 0; i < n; i++) {
            map.put(keys[i], vals[i]);
        }
    }
}
