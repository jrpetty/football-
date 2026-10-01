package com.jrpetty.mcassistant.village;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every village's history, kept with the world: when it was founded, every building
 * that went up, every age it came into, who was born, who took up with whom, who
 * died, whose dream came true, and what players did for it. Folk tell it from
 * memory; the village will hand you a copy of it as a book.
 *
 * <p>The rest of a village's state rides on its folk (see Villages.restore). Its
 * history is too long for that, so it lives in the world's own data.
 */
public final class Chronicle extends SavedData {

    private static final String ID = "mc_assistant_chronicle";
    private static final int KEEP = 400;

    public record Entry(long day, String text) {}

    private final Map<UUID, List<Entry>> history = new HashMap<>();
    private final Map<UUID, Long> founded = new HashMap<>();

    @Nullable
    private static Chronicle of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Chronicle::new, Chronicle::load, null), ID);
    }

    /** Write a line into a village's history. */
    public static void record(UUID village, long day, String text) {
        Chronicle c = of();
        if (c == null) return;
        List<Entry> lines = c.history.computeIfAbsent(village, k -> new ArrayList<>());
        if (!lines.isEmpty()) {
            Entry last = lines.get(lines.size() - 1);
            if (last.day() == day && last.text().equals(text)) return;
        }
        lines.add(new Entry(day, text));
        while (lines.size() > KEEP) lines.remove(1);      // keep the founding, lose the middle
        c.founded.putIfAbsent(village, day);
        c.setDirty();
    }

    public static List<Entry> of(UUID village) {
        Chronicle c = of();
        if (c == null) return List.of();
        List<Entry> lines = c.history.get(village);
        return lines == null ? List.of() : new ArrayList<>(lines);
    }

    /** The day the village's history begins, or -1. */
    public static long foundedOn(UUID village) {
        Chronicle c = of();
        return c == null ? -1 : c.founded.getOrDefault(village, -1L);
    }

    public static Chronicle load(CompoundTag tag, HolderLookup.Provider registries) {
        Chronicle c = new Chronicle();
        for (Tag t : tag.getList("Villages", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) t;
            if (!v.hasUUID("Id")) continue;
            UUID id = v.getUUID("Id");
            List<Entry> lines = new ArrayList<>();
            for (Tag e : v.getList("Lines", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) e;
                lines.add(new Entry(one.getLong("Day"), one.getString("Text")));
            }
            c.history.put(id, lines);
            if (v.contains("Founded")) c.founded.put(id, v.getLong("Founded"));
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, List<Entry>> e : history.entrySet()) {
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", e.getKey());
            Long f = founded.get(e.getKey());
            if (f != null) v.putLong("Founded", f);
            ListTag lines = new ListTag();
            for (Entry en : e.getValue()) {
                CompoundTag one = new CompoundTag();
                one.putLong("Day", en.day());
                one.putString("Text", en.text());
                lines.add(one);
            }
            v.put("Lines", lines);
            all.add(v);
        }
        tag.put("Villages", all);
        return tag;
    }
}
