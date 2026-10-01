package com.jrpetty.mcassistant.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * A village's register of what it has built and where, kept with the world: every
 * building's drawing, the ground it stands on and which way its back is. The town's
 * life (entity/TownLife) is hung on it — the lights in its windows, the smoke from
 * its chimneys, the number by its door.
 *
 * <p>The rest of a village's state rides on its folk (see Villages.restore); a town
 * of a hundred buildings is too much for every folk to carry, so it lives here.
 */
public final class Ledger extends SavedData {

    private static final String ID = "mc_assistant_ledger";

    /** One building: its drawing, its anchor (the ground at its centre) and which way its back is. */
    public record Building(String structure, BlockPos anchor, Direction facing) {}

    private final Map<UUID, List<Building>> buildings = new HashMap<>();
    /** The village's treasury, in coin; absent until it is first opened. */
    private final Map<UUID, Integer> treasury = new HashMap<>();
    /** The last day each village paid its wages. */
    private final Map<UUID, Long> paid = new HashMap<>();
    /** Each colony's mother village. */
    private final Map<UUID, UUID> mother = new HashMap<>();
    /** How far the road from each colony's mother to it has got: the next step, and the last step's height. */
    private final Map<UUID, int[]> road = new HashMap<>();
    /** When each colony's last caravan set out. */
    private final Map<UUID, Long> caravan = new HashMap<>();

    /** Somebody of the village who has died: for the graveyard, the chapel's memorial and the register. */
    public record Grave(String name, long born, long died, String cause, String parents, String partner, String trade) {}

    /** The village's dead, in the order they died. */
    private final Map<UUID, List<Grave>> graves = new HashMap<>();

    /** The village's citizens: the players it counts as its own, by name. */
    private final Map<UUID, Map<UUID, String>> citizens = new HashMap<>();
    /** What each player has done against each village's laws: offences, coin owed, banished until (day). */
    private final Map<UUID, Map<UUID, int[]>> offences = new HashMap<>();
    /** What each pair of villages thinks of the other, -100 to 100 ("a|b", the lesser id first). */
    private final Map<String, Integer> relations = new HashMap<>();
    /** The players each village has raised a statue to. */
    private final Map<UUID, Map<UUID, String>> statues = new HashMap<>();
    /** The houses each village has given a second storey (by anchor). */
    private final Map<UUID, java.util.Set<Long>> grown = new HashMap<>();
    /** Small things a village keeps (the elder's order...), by key. */
    private final Map<UUID, Map<String, String>> notes = new HashMap<>();
    /** The houses whose old roof is off, their second storey going up (by anchor). */
    private final Map<UUID, java.util.Set<Long>> raising = new HashMap<>();

    /** Without a server (a plain unit test) the register is kept here instead. */
    private static Ledger loose;

    @Nullable
    private static Ledger of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Ledger();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(Ledger::new, Ledger::load, null), ID);
    }

    /** A building went up here. The same ground twice is one building (a restart, a retry). */
    public static void built(UUID village, String structure, BlockPos anchor, Direction facing) {
        Ledger l = of();
        if (l == null) return;
        List<Building> all = l.buildings.computeIfAbsent(village, k -> new ArrayList<>());
        for (Building b : all) {
            if (b.anchor().equals(anchor) && b.structure().equals(structure)) return;
        }
        all.add(new Building(structure, anchor.immutable(), facing));
        l.setDirty();
    }

    /** Everything this village has built, in the order it went up. */
    public static List<Building> buildings(UUID village) {
        Ledger l = of();
        if (l == null) return List.of();
        List<Building> all = l.buildings.get(village);
        return all == null ? List.of() : new ArrayList<>(all);
    }

    /** The village is gone: forget its buildings. */
    public static void forget(UUID village) {
        Ledger l = of();
        if (l == null) return;
        boolean any = l.buildings.remove(village) != null;
        any |= l.treasury.remove(village) != null;
        any |= l.paid.remove(village) != null;
        any |= l.mother.remove(village) != null;
        any |= l.road.remove(village) != null;
        any |= l.caravan.remove(village) != null;
        any |= l.graves.remove(village) != null;
        if (any) l.setDirty();
    }

    // ------------------------------------------------------------------ colonies, roads, caravans

    /** This colony was founded from that village. */
    public static void link(UUID motherVillage, UUID colony) {
        Ledger l = of();
        if (l == null || motherVillage.equals(colony)) return;
        l.mother.put(colony, motherVillage);
        l.setDirty();
    }

    /** Every colony and its mother village. */
    public static Map<UUID, UUID> links() {
        Ledger l = of();
        return l == null ? Map.of() : new HashMap<>(l.mother);
    }

    /** The colony's road: {next step, height of the last step, done (1/0)}, or null if not begun. */
    @Nullable
    public static int[] road(UUID colony) {
        Ledger l = of();
        if (l == null) return null;
        int[] r = l.road.get(colony);
        return r == null ? null : r.clone();
    }

    public static void road(UUID colony, int next, int lastY, boolean done) {
        Ledger l = of();
        if (l == null) return;
        l.road.put(colony, new int[]{ next, lastY, done ? 1 : 0 });
        l.setDirty();
    }

    /** When the colony's last caravan set out (game time), or -1. */
    public static long caravanAt(UUID colony) {
        Ledger l = of();
        return l == null ? -1 : l.caravan.getOrDefault(colony, -1L);
    }

    public static void caravanAt(UUID colony, long gameTime) {
        Ledger l = of();
        if (l == null) return;
        l.caravan.put(colony, gameTime);
        l.setDirty();
    }

    // ------------------------------------------------------------------ the treasury

    /** Has this village a treasury yet? */
    public static boolean hasTreasury(UUID village) {
        Ledger l = of();
        return l != null && l.treasury.containsKey(village);
    }

    /** The coin in the village's treasury. */
    public static int coins(UUID village) {
        Ledger l = of();
        return l == null ? 0 : l.treasury.getOrDefault(village, 0);
    }

    /** Coin into the treasury (minted, or paid in at the stalls). */
    public static void addCoins(UUID village, int n) {
        Ledger l = of();
        if (l == null || n < 0) return;
        l.treasury.merge(village, n, Integer::sum);
        l.setDirty();
    }

    /** Coin out of the treasury, as much as there is up to {@code n}; returns how much. */
    public static int takeCoins(UUID village, int n) {
        Ledger l = of();
        if (l == null || n <= 0) return 0;
        int have = l.treasury.getOrDefault(village, 0);
        int took = Math.min(have, n);
        if (took > 0) {
            l.treasury.put(village, have - took);
            l.setDirty();
        }
        return took;
    }

    /** The last day the village paid its wages, or -1. */
    public static long paidOn(UUID village) {
        Ledger l = of();
        return l == null ? -1 : l.paid.getOrDefault(village, -1L);
    }

    public static void paid(UUID village, long day) {
        Ledger l = of();
        if (l == null) return;
        l.paid.put(village, day);
        l.setDirty();
    }

    /** Somebody died: into the village's record of its dead. */
    public static void buried(UUID village, Grave g) {
        Ledger l = of();
        if (l == null) return;
        l.graves.computeIfAbsent(village, k -> new ArrayList<>()).add(g);
        l.setDirty();
    }

    /** The village's dead, first to last. */
    public static List<Grave> graves(UUID village) {
        Ledger l = of();
        if (l == null) return List.of();
        List<Grave> g = l.graves.get(village);
        return g == null ? List.of() : List.copyOf(g);
    }

    // ------------------------------------------------------------------ citizens, laws, neighbours

    public static boolean citizen(UUID village, UUID player) {
        Ledger l = of();
        return l != null && l.citizens.getOrDefault(village, Map.of()).containsKey(player);
    }

    public static void addCitizen(UUID village, UUID player, String name) {
        Ledger l = of();
        if (l == null) return;
        l.citizens.computeIfAbsent(village, k -> new HashMap<>()).put(player, name);
        l.setDirty();
    }

    public static void removeCitizen(UUID village, UUID player) {
        Ledger l = of();
        if (l == null) return;
        Map<UUID, String> m = l.citizens.get(village);
        if (m != null && m.remove(player) != null) l.setDirty();
    }

    public static Map<UUID, String> citizens(UUID village) {
        Ledger l = of();
        if (l == null) return Map.of();
        return Map.copyOf(l.citizens.getOrDefault(village, Map.of()));
    }

    /** A player's record against a village's laws: {offences, coin owed, banished until (day)}. */
    public static int[] record(UUID village, UUID player) {
        Ledger l = of();
        if (l == null) return new int[3];
        int[] r = l.offences.getOrDefault(village, Map.of()).get(player);
        return r == null ? new int[3] : r.clone();
    }

    public static void record(UUID village, UUID player, int[] r) {
        Ledger l = of();
        if (l == null) return;
        l.offences.computeIfAbsent(village, k -> new HashMap<>()).put(player, r.clone());
        l.setDirty();
    }

    public static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** What two villages think of each other (0 if they have never had dealings). */
    public static int relation(UUID a, UUID b) {
        Ledger l = of();
        return l == null ? 0 : l.relations.getOrDefault(pair(a, b), 0);
    }

    public static boolean knowEachOther(UUID a, UUID b) {
        Ledger l = of();
        return l != null && l.relations.containsKey(pair(a, b));
    }

    public static int relate(UUID a, UUID b, int delta) {
        Ledger l = of();
        if (l == null) return 0;
        int now = Math.max(-100, Math.min(100, l.relations.getOrDefault(pair(a, b), 0) + delta));
        l.relations.put(pair(a, b), now);
        l.setDirty();
        return now;
    }

    public static boolean statue(UUID village, UUID player) {
        Ledger l = of();
        return l != null && l.statues.getOrDefault(village, Map.of()).containsKey(player);
    }

    public static int statues(UUID village) {
        Ledger l = of();
        return l == null ? 0 : l.statues.getOrDefault(village, Map.of()).size();
    }

    public static void raisedStatue(UUID village, UUID player, String name) {
        Ledger l = of();
        if (l == null) return;
        l.statues.computeIfAbsent(village, k -> new HashMap<>()).put(player, name);
        l.setDirty();
    }

    /** Has the house at this anchor been given its second storey? */
    public static boolean grown(UUID village, BlockPos anchor) {
        Ledger l = of();
        return l != null && l.grown.getOrDefault(village, java.util.Set.of()).contains(anchor.asLong());
    }

    public static void grow(UUID village, BlockPos anchor) {
        Ledger l = of();
        if (l == null) return;
        if (l.grown.computeIfAbsent(village, k -> new java.util.HashSet<>()).add(anchor.asLong())) l.setDirty();
    }

    /** Is the old roof off the house at this anchor (its second storey going up)? */
    public static boolean raising(UUID village, BlockPos anchor) {
        Ledger l = of();
        return l != null && l.raising.getOrDefault(village, java.util.Set.of()).contains(anchor.asLong());
    }

    public static void raising(UUID village, BlockPos anchor, boolean on) {
        Ledger l = of();
        if (l == null) return;
        java.util.Set<Long> set = l.raising.computeIfAbsent(village, k -> new java.util.HashSet<>());
        if (on ? set.add(anchor.asLong()) : set.remove(anchor.asLong())) l.setDirty();
    }

    @Nullable
    public static String note(UUID village, String key) {
        Ledger l = of();
        return l == null ? null : l.notes.getOrDefault(village, Map.of()).get(key);
    }

    public static void note(UUID village, String key, String value) {
        Ledger l = of();
        if (l == null) return;
        l.notes.computeIfAbsent(village, k -> new HashMap<>()).put(key, value);
        l.setDirty();
    }

    public static int grownCount(UUID village) {
        Ledger l = of();
        return l == null ? 0 : l.grown.getOrDefault(village, java.util.Set.of()).size();
    }

    public static Ledger load(CompoundTag tag, HolderLookup.Provider registries) {
        Ledger l = new Ledger();
        for (Tag t : tag.getList("Villages", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) t;
            if (!v.hasUUID("Id")) continue;
            List<Building> all = new ArrayList<>();
            for (Tag e : v.getList("Buildings", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) e;
                Direction d = Direction.from2DDataValue(b.getInt("Facing"));
                all.add(new Building(b.getString("Name"), BlockPos.of(b.getLong("At")), d));
            }
            UUID id = v.getUUID("Id");
            if (!all.isEmpty()) l.buildings.put(id, all);
            if (v.contains("Coins")) l.treasury.put(id, v.getInt("Coins"));
            if (v.contains("Paid")) l.paid.put(id, v.getLong("Paid"));
            if (v.hasUUID("Mother")) l.mother.put(id, v.getUUID("Mother"));
            if (v.contains("Road")) l.road.put(id, v.getIntArray("Road"));
            if (v.contains("Caravan")) l.caravan.put(id, v.getLong("Caravan"));
            List<Grave> dead = new ArrayList<>();
            for (Tag e : v.getList("Graves", Tag.TAG_COMPOUND)) {
                CompoundTag g = (CompoundTag) e;
                dead.add(new Grave(g.getString("Name"), g.getLong("Born"), g.getLong("Died"), g.getString("Cause"),
                    g.getString("Parents"), g.getString("Partner"), g.getString("Trade")));
            }
            if (!dead.isEmpty()) l.graves.put(id, dead);
            for (Tag e : v.getList("Citizens", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                if (c.hasUUID("Id")) l.citizens.computeIfAbsent(id, k -> new HashMap<>()).put(c.getUUID("Id"), c.getString("Name"));
            }
            for (Tag e : v.getList("Offences", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                if (c.hasUUID("Id")) l.offences.computeIfAbsent(id, k -> new HashMap<>()).put(c.getUUID("Id"), java.util.Arrays.copyOf(c.getIntArray("R"), 3));
            }
            CompoundTag kept = v.getCompound("Notes");
            for (String k : kept.getAllKeys()) l.notes.computeIfAbsent(id, x -> new HashMap<>()).put(k, kept.getString(k));
            long[] raisingAt = v.getLongArray("Raising");
            if (raisingAt.length > 0) {
                java.util.Set<Long> set = new java.util.HashSet<>();
                for (long g : raisingAt) set.add(g);
                l.raising.put(id, set);
            }
            long[] grownAt = v.getLongArray("Grown");
            if (grownAt.length > 0) {
                java.util.Set<Long> set = new java.util.HashSet<>();
                for (long g : grownAt) set.add(g);
                l.grown.put(id, set);
            }
            for (Tag e : v.getList("Statues", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                if (c.hasUUID("Id")) l.statues.computeIfAbsent(id, k -> new HashMap<>()).put(c.getUUID("Id"), c.getString("Name"));
            }
        }
        CompoundTag rel = tag.getCompound("Relations");
        for (String k : rel.getAllKeys()) l.relations.put(k, rel.getInt(k));
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villages = new ListTag();
        java.util.Set<UUID> ids = new java.util.HashSet<>(buildings.keySet());
        ids.addAll(treasury.keySet());
        ids.addAll(paid.keySet());
        ids.addAll(mother.keySet());
        ids.addAll(graves.keySet());
        ids.addAll(citizens.keySet());
        ids.addAll(offences.keySet());
        ids.addAll(statues.keySet());
        ids.addAll(grown.keySet());
        ids.addAll(raising.keySet());
        ids.addAll(notes.keySet());
        for (UUID id : ids) {
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", id);
            if (treasury.containsKey(id)) v.putInt("Coins", treasury.get(id));
            if (paid.containsKey(id)) v.putLong("Paid", paid.get(id));
            if (mother.containsKey(id)) v.putUUID("Mother", mother.get(id));
            if (road.containsKey(id)) v.putIntArray("Road", road.get(id));
            if (caravan.containsKey(id)) v.putLong("Caravan", caravan.get(id));
            ListTag all = new ListTag();
            for (Building b : buildings.getOrDefault(id, List.of())) {
                CompoundTag one = new CompoundTag();
                one.putString("Name", b.structure());
                one.putLong("At", b.anchor().asLong());
                one.putInt("Facing", b.facing().get2DDataValue());
                all.add(one);
            }
            v.put("Buildings", all);
            ListTag dead = new ListTag();
            for (Grave g : graves.getOrDefault(id, List.of())) {
                CompoundTag one = new CompoundTag();
                one.putString("Name", g.name());
                one.putLong("Born", g.born());
                one.putLong("Died", g.died());
                one.putString("Cause", g.cause());
                one.putString("Parents", g.parents());
                one.putString("Partner", g.partner());
                one.putString("Trade", g.trade());
                dead.add(one);
            }
            if (!dead.isEmpty()) v.put("Graves", dead);
            ListTag people = new ListTag();
            for (Map.Entry<UUID, String> e : citizens.getOrDefault(id, Map.of()).entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Id", e.getKey());
                one.putString("Name", e.getValue());
                people.add(one);
            }
            if (!people.isEmpty()) v.put("Citizens", people);
            ListTag crimes = new ListTag();
            for (Map.Entry<UUID, int[]> e : offences.getOrDefault(id, Map.of()).entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Id", e.getKey());
                one.putIntArray("R", e.getValue());
                crimes.add(one);
            }
            if (!crimes.isEmpty()) v.put("Offences", crimes);
            ListTag raised = new ListTag();
            for (Map.Entry<UUID, String> e : statues.getOrDefault(id, Map.of()).entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putUUID("Id", e.getKey());
                one.putString("Name", e.getValue());
                raised.add(one);
            }
            if (!raised.isEmpty()) v.put("Statues", raised);
            Map<String, String> kept = notes.getOrDefault(id, Map.of());
            if (!kept.isEmpty()) {
                CompoundTag n = new CompoundTag();
                for (Map.Entry<String, String> e : kept.entrySet()) n.putString(e.getKey(), e.getValue());
                v.put("Notes", n);
            }
            java.util.Set<Long> going = raising.getOrDefault(id, java.util.Set.of());
            if (!going.isEmpty()) {
                long[] arr = new long[going.size()];
                int i = 0;
                for (long g : going) arr[i++] = g;
                v.putLongArray("Raising", arr);
            }
            java.util.Set<Long> up = grown.getOrDefault(id, java.util.Set.of());
            if (!up.isEmpty()) {
                long[] arr = new long[up.size()];
                int i = 0;
                for (long g : up) arr[i++] = g;
                v.putLongArray("Grown", arr);
            }
            villages.add(v);
        }
        tag.put("Villages", villages);
        CompoundTag rel = new CompoundTag();
        for (Map.Entry<String, Integer> e : relations.entrySet()) rel.putInt(e.getKey(), e.getValue());
        tag.put("Relations", rel);
        return tag;
    }
}
