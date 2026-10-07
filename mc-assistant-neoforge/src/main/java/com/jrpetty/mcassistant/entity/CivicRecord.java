package com.jrpetty.mcassistant.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [civic] What the town's votes and its newcomers keep with the world: the questions put to the whole town and
 * how they went (Referendums), the great work under way and those finished (BigWorks), and the parties of
 * newcomers on the road, camped at a town's edge or settled (Newcomers). One compound for each town, one for each
 * folk, and the parties by number; written down as it changes, so a vote half cast, a bridge half built or a
 * family waiting by the road when the world was saved are all still there when it is loaded.
 */
public final class CivicRecord extends SavedData {

    private static final String ID = "mc_assistant_civic_votes";

    /** "towns", "folk" and "parties" (each a compound), and "next" (the last number handed out). */
    private CompoundTag root = new CompoundTag();
    /** With no server about (never in a game; a safeguard for a unit test), a record that lives as long as the class. */
    @Nullable private static CivicRecord loose;

    public CivicRecord() {}

    static CivicRecord of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new CivicRecord();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(CivicRecord::new, CivicRecord::load, null), ID);
    }

    /** A town's own compound (made the first time it is asked for). */
    static CompoundTag town(UUID village) {
        return sub(sub(of().root, "towns"), village.toString());
    }

    /** A folk's own compound (made the first time it is asked for). */
    static CompoundTag folk(UUID folk) {
        return sub(sub(of().root, "folk"), folk.toString());
    }

    /** Is anything kept for this folk yet? (Asking for it with folk() would make an empty one.) */
    static boolean known(UUID folk) {
        return sub(of().root, "folk").contains(folk.toString(), Tag.TAG_COMPOUND);
    }

    /** Every party of newcomers there is, by its number. */
    static CompoundTag parties() {
        return sub(of().root, "parties");
    }

    /** A fresh number for a question or a party: never the same twice in a world. */
    static int nextId() {
        CompoundTag r = of().root;
        int n = r.getInt("next") + 1;
        r.putInt("next", n);
        changed();
        return n;
    }

    static void changed() {
        of().setDirty();
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    /** The list kept under this key, itself, whatever it holds (made the first time it is asked for). */
    static ListTag list(CompoundTag t, String key) {
        if (!(t.get(key) instanceof ListTag)) t.put(key, new ListTag());
        return (ListTag) t.get(key);
    }

    public static CivicRecord load(CompoundTag tag, HolderLookup.Provider registries) {
        CivicRecord c = new CivicRecord();
        c.root = tag.getCompound("Root");
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Root", root.copy());
        return tag;
    }

    /** Everything forgotten (the tests share one world). */
    static void wipeForTests() {
        of().root = new CompoundTag();
        changed();
    }
}
