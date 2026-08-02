package dev.structint.world;
import net.minecraft.server.level.ServerLevel;
public final class StructuralManager {
    static void enqueueChange(ServerLevel l, long p){ harness.W.REQUEUED.add(String.valueOf(p)); }
}
