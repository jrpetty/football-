package net.minecraft.server;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
public class MinecraftServer {
    public List<ServerLevel> levels;
    public Iterable<ServerLevel> getAllLevels() { return levels; }
}
