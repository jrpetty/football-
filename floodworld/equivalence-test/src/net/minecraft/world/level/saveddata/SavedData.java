package net.minecraft.world.level.saveddata;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
public abstract class SavedData {
    public int dirtyCount;
    public void setDirty() { dirtyCount++; }
    public abstract CompoundTag save(CompoundTag tag, HolderLookup.Provider registries);
    public static class Factory<T extends SavedData> {
        public final Supplier<T> ctor;
        public Factory(Supplier<T> ctor, BiFunction<CompoundTag, HolderLookup.Provider, T> loader, DataFixTypes type) { this.ctor = ctor; }
    }
}
