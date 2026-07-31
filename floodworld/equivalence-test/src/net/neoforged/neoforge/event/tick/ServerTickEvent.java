package net.neoforged.neoforge.event.tick;
import net.minecraft.server.MinecraftServer;
public class ServerTickEvent {
    public static class Post {
        public MinecraftServer server;
        public MinecraftServer getServer() { return server; }
    }
}
