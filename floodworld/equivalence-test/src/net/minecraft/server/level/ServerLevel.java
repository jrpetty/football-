package net.minecraft.server.level;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
public class ServerLevel extends Level {
    private final ResourceKey<Level> dim;
    private final boolean weather;
    private final DimensionDataStorage storage = new DimensionDataStorage();
    private final ServerChunkCache source = new ServerChunkCache();
    public final List<ServerPlayer> players = new ArrayList<>();
    public RandomSource random;
    public ServerLevel(String id, boolean weather) { this.dim = new ResourceKey<>(id); this.weather = weather; }
    public ServerChunkCache getChunkSource() { return source; }
    public DimensionDataStorage getDataStorage() { return storage; }
    public ResourceKey<Level> dimension() { return dim; }
    public List<ServerPlayer> players() { return players; }
    @Override public boolean isRaining() { return weather && harness.World.raining; }
    @Override public RandomSource getRandom() { return random; }
}
