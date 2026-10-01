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
        if (l != null && l.buildings.remove(village) != null) l.setDirty();
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
            l.buildings.put(v.getUUID("Id"), all);
        }
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villages = new ListTag();
        for (Map.Entry<UUID, List<Building>> e : buildings.entrySet()) {
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", e.getKey());
            ListTag all = new ListTag();
            for (Building b : e.getValue()) {
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
