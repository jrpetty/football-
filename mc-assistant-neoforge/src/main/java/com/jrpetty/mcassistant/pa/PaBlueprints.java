package com.jrpetty.mcassistant.pa;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
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
 * [featpa] The buildings a player has shown their assistant, kept by name in its menu (Orders > Blueprints): its own
 * saved data, keyed by player, apart from the assistant's record (a body lost and stood up again keeps them).
 */
public final class PaBlueprints extends SavedData {

    public static final String ID = "mc_assistant_pa_blueprints";
    /** The most a player keeps (the oldest go first). */
    public static final int MOST = 24;

    private final Map<UUID, List<CompoundTag>> owners = new HashMap<>();

    @Nullable
    static PaBlueprints get() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PaBlueprints::new, PaBlueprints::load, null), ID);
    }

    /** A player's blueprints, newest first. */
    public static List<PaBlueprint> of(UUID owner) {
        PaBlueprints s = get();
        List<PaBlueprint> out = new ArrayList<>();
        if (s == null) return out;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        var blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        for (CompoundTag t : s.owners.getOrDefault(owner, List.of())) out.add(PaBlueprint.load(t, blocks));
        return out;
    }

    /** Names only (cheap: no cells read). */
    public static List<String> names(UUID owner) {
        PaBlueprints s = get();
        List<String> out = new ArrayList<>();
        if (s != null) for (CompoundTag t : s.owners.getOrDefault(owner, List.of())) out.add(t.getString("N"));
        return out;
    }

    @Nullable
    public static PaBlueprint find(UUID owner, String name) {
        PaBlueprints s = get();
        if (s == null) return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        for (CompoundTag t : s.owners.getOrDefault(owner, List.of()))
            if (t.getString("N").equalsIgnoreCase(name)) return PaBlueprint.load(t, server.registryAccess().lookupOrThrow(Registries.BLOCK));
        return null;
    }

    /** Keep one (a name already kept is replaced). */
    public static void put(UUID owner, PaBlueprint bp) {
        PaBlueprints s = get();
        if (s == null) return;
        List<CompoundTag> l = s.owners.computeIfAbsent(owner, k -> new ArrayList<>());
        l.removeIf(t -> t.getString("N").equalsIgnoreCase(bp.name));
        l.add(0, bp.save());
        while (l.size() > MOST) l.remove(l.size() - 1);
        s.setDirty();
    }

    public static boolean remove(UUID owner, String name) {
        PaBlueprints s = get();
        if (s == null) return false;
        boolean gone = s.owners.getOrDefault(owner, new ArrayList<>()).removeIf(t -> t.getString("N").equalsIgnoreCase(name));
        if (gone) s.setDirty();
        return gone;
    }

    public static boolean rename(UUID owner, String from, String to) {
        PaBlueprints s = get();
        if (s == null || to.isBlank()) return false;
        List<CompoundTag> l = s.owners.getOrDefault(owner, List.of());
        for (CompoundTag t : l) if (t.getString("N").equalsIgnoreCase(to)) return false;
        for (CompoundTag t : l) if (t.getString("N").equalsIgnoreCase(from)) { t.putString("N", to); s.setDirty(); return true; }
        return false;
    }

    /** A name not yet used: "Building", "Building 2", ... */
    public static String freshName(UUID owner, String base) {
        List<String> n = names(owner);
        String b = base == null || base.isBlank() ? "Building" : base.trim();
        if (n.stream().noneMatch(b::equalsIgnoreCase)) return b;
        for (int i = 2; ; i++) if (n.stream().noneMatch((b + " " + i)::equalsIgnoreCase)) return b + " " + i;
    }

    public static void resetForTests() {
        PaBlueprints s = get();
        if (s == null) return;
        s.owners.clear();
        s.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag os = new ListTag();
        for (Map.Entry<UUID, List<CompoundTag>> e : owners.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("U", e.getKey());
            ListTag l = new ListTag();
            l.addAll(e.getValue());
            t.put("B", l);
            os.add(t);
        }
        tag.put("Owners", os);
        return tag;
    }

    public static PaBlueprints load(CompoundTag tag, HolderLookup.Provider registries) {
        PaBlueprints s = new PaBlueprints();
        for (Tag e : tag.getList("Owners", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            List<CompoundTag> l = new ArrayList<>();
            for (Tag b : t.getList("B", Tag.TAG_COMPOUND)) l.add((CompoundTag) b);
            s.owners.put(t.getUUID("U"), l);
        }
        return s;
    }
}
