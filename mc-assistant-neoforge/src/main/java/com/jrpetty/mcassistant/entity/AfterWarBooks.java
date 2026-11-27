package com.jrpetty.mcassistant.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;

/**
 * [war-after] The books kept with the world for what comes after a war, which no town's own books can hold: the
 * ruins (a ruin's town is gone, and its Ledger with it) and the outlaws (a player's record with a town, its bounty in
 * hand, the hunters' last ride). Ruins by the dead town's id; outlaws by town, then by player. The prisoners are their
 * captors' own business and are kept in the captor's Ledger notes (Prisoners), so they go with the town that holds them.
 */
public final class AfterWarBooks extends SavedData {

    private static final String ID = "mc_assistant_after_war";

    private CompoundTag ruins = new CompoundTag(), outlaws = new CompoundTag();
    @Nullable private static AfterWarBooks loose;

    public AfterWarBooks() {}

    static AfterWarBooks of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new AfterWarBooks();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(AfterWarBooks::new, AfterWarBooks::load, null), ID);
    }

    public static AfterWarBooks load(CompoundTag tag, HolderLookup.Provider registries) {
        AfterWarBooks b = new AfterWarBooks();
        b.ruins = tag.getCompound("ruins");
        b.outlaws = tag.getCompound("outlaws");
        return b;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("ruins", ruins.copy());
        tag.put("outlaws", outlaws.copy());
        return tag;
    }

    static void changed() {
        of().setDirty();
    }

    /** Every ruin, by the id the town had. */
    static CompoundTag ruins() {
        return of().ruins;
    }

    /** A town's book of the players it has dealt with as the law (made the first time it is asked for). */
    static CompoundTag outlawsOf(String town) {
        AfterWarBooks b = of();
        if (!b.outlaws.contains(town, Tag.TAG_COMPOUND)) b.outlaws.put(town, new CompoundTag());
        return b.outlaws.getCompound(town);
    }

    /** Every town's book of players (town id to its book). */
    static CompoundTag outlaws() {
        return of().outlaws;
    }
}
