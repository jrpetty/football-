package net.minecraft.world.level.chunk;
import net.minecraft.world.level.levelgen.Heightmap;
public abstract class ChunkAccess {
    protected int cx, cz;
    /** Vanilla contract: getFirstAvailable(x & 15, z & 15) - 1, i.e. the topmost occupied block. */
    public int getHeight(Heightmap.Types type, int x, int z) {
        harness.C.chunkGetHeight++;
        int wx = (cx << 4) | (x & 15);
        int wz = (cz << 4) | (z & 15);
        return harness.World.motionBlockingTop(wx, wz);
    }
}
