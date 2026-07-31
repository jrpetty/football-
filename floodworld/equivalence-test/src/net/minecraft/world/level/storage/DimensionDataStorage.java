package net.minecraft.world.level.storage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.saveddata.SavedData;
public class DimensionDataStorage {
    public final Map<String, SavedData> cache = new HashMap<>();
    @SuppressWarnings("unchecked")
    public <T extends SavedData> T computeIfAbsent(SavedData.Factory<T> factory, String name) {
        return (T) cache.computeIfAbsent(name, n -> factory.ctor.get());
    }
}
