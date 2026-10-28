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

    /**
     * A player a village has taken to its heart: the house it resolved to build them
     * (and where it stands, once it does), whether they have its key, and whether the
     * village has named them its hero.
     */
    public static final class Guest {
        public final UUID player;
        public String name;
        public long x, y, z;
        public boolean built, keyGiven, hero, medalGiven;

        Guest(UUID player, String name) {
            this.player = player;
            this.name = name;
        }
    }

    private final Map<UUID, List<Guest>> guests = new HashMap<>();
    /** The last day each player was seen in each village. */
    private final Map<UUID, Map<UUID, Long>> visits = new HashMap<>();

    /** Note a player's visit; returns the day of their previous one, or -1. */
    public static long visited(UUID village, UUID player, long day) {
        Chronicle c = of();
        if (c == null) return -1;
        Map<UUID, Long> v = c.visits.computeIfAbsent(village, k -> new HashMap<>());
        Long before = v.put(player, day);
        if (before == null || before != day) c.setDirty();
        return before == null ? -1 : before;
    }

    /** This village's record of this player, or null. */
    @Nullable
    public static Guest guest(UUID village, UUID player) {
        Chronicle c = of();
        if (c == null) return null;
        for (Guest g : c.guests.getOrDefault(village, List.of())) if (g.player.equals(player)) return g;
        return null;
    }

    public static Guest welcome(UUID village, UUID player, String name) {
        Chronicle c = of();
        Guest g = guest(village, player);
        if (g != null || c == null) return g;
        g = new Guest(player, name);
        c.guests.computeIfAbsent(village, k -> new ArrayList<>()).add(g);
        c.setDirty();
        return g;
    }

    /** The guest whose house the village still has to build, if any. */
    @Nullable
    public static Guest awaitingAHouse(UUID village) {
        Chronicle c = of();
        if (c == null) return null;
        for (Guest g : c.guests.getOrDefault(village, List.of())) if (!g.built) return g;
        return null;
    }

    public static List<Guest> guests(UUID village) {
        Chronicle c = of();
        return c == null ? List.of() : new ArrayList<>(c.guests.getOrDefault(village, List.of()));
    }

    /** Something about a guest changed: write it down. */
    public static void touch() {
        Chronicle c = of();
        if (c != null) c.setDirty();
    }

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
            List<Guest> gs = new ArrayList<>();
            for (Tag e : v.getList("Guests", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) e;
                if (!one.hasUUID("Player")) continue;
                Guest g = new Guest(one.getUUID("Player"), one.getString("Name"));
                g.x = one.getLong("X");
                g.y = one.getLong("Y");
                g.z = one.getLong("Z");
                g.built = one.getBoolean("Built");
                g.keyGiven = one.getBoolean("Key");
                g.hero = one.getBoolean("Hero");
                g.medalGiven = one.getBoolean("Medal");
                gs.add(g);
            }
            if (!gs.isEmpty()) c.guests.put(id, gs);
            Map<UUID, Long> seen = new HashMap<>();
            for (Tag e : v.getList("Visits", Tag.TAG_COMPOUND)) {
                CompoundTag one = (CompoundTag) e;
                if (one.hasUUID("Player")) seen.put(one.getUUID("Player"), one.getLong("Day"));
            }
            if (!seen.isEmpty()) c.visits.put(id, seen);
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
            ListTag gs = new ListTag();
            for (Guest g : guests.getOrDefault(e.getKey(), List.of())) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Player", g.player);
                one.putString("Name", g.name);
                one.putLong("X", g.x);
                one.putLong("Y", g.y);
                one.putLong("Z", g.z);
                one.putBoolean("Built", g.built);
                one.putBoolean("Key", g.keyGiven);
                one.putBoolean("Hero", g.hero);
                one.putBoolean("Medal", g.medalGiven);
                gs.add(one);
            }
            v.put("Guests", gs);
            ListTag seen = new ListTag();
            for (Map.Entry<UUID, Long> en : visits.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Player", en.getKey());
                one.putLong("Day", en.getValue());
                seen.add(one);
            }
            v.put("Visits", seen);
            all.add(v);
        }
        // A village with guests but no history yet (not likely, but never lose a house).
        for (Map.Entry<UUID, List<Guest>> e : guests.entrySet()) {
            if (history.containsKey(e.getKey())) continue;
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", e.getKey());
            v.put("Lines", new ListTag());
            ListTag gs = new ListTag();
            for (Guest g : e.getValue()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Player", g.player);
                one.putString("Name", g.name);
                one.putLong("X", g.x);
                one.putLong("Y", g.y);
                one.putLong("Z", g.z);
                one.putBoolean("Built", g.built);
                one.putBoolean("Key", g.keyGiven);
                one.putBoolean("Hero", g.hero);
                one.putBoolean("Medal", g.medalGiven);
                gs.add(one);
            }
            v.put("Guests", gs);
            all.add(v);
        }
        tag.put("Villages", all);
        return tag;
    }
}
