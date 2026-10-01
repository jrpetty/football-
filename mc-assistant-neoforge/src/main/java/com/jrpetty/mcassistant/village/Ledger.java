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
        }
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villages = new ListTag();
        java.util.Set<UUID> ids = new java.util.HashSet<>(buildings.keySet());
        ids.addAll(treasury.keySet());
        ids.addAll(paid.keySet());
        ids.addAll(mother.keySet());
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
            villages.add(v);
        }
        tag.put("Villages", villages);
        return tag;
    }
}
