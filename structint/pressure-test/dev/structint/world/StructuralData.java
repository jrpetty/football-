package dev.structint.world;
import net.minecraft.core.BlockPos; import net.minecraft.server.level.ServerLevel; import net.minecraft.world.level.chunk.LevelChunk;
public final class StructuralData {
    public static boolean isManaged(ServerLevel l, BlockPos p){ return harness.W.MANAGED.contains(p.asLong()); }
    public static boolean isChunkLoaded(ServerLevel l, BlockPos p){ return true; }
    public static LevelChunk loadedChunk(ServerLevel l,int cx,int cz){ return new LevelChunk(cx,cz); }
    /** Every managed block whose chunk this is. */
    public static ManagedBlocks managed(LevelChunk c){
        ManagedBlocks m=new ManagedBlocks();
        for(long k: harness.W.MANAGED) if((BlockPos.getX(k)>>4)==c.cx && (BlockPos.getZ(k)>>4)==c.cz) m.add(k);
        return m;
    }
    public static void setManaged(ServerLevel l,BlockPos p,boolean m){ if(!m) harness.W.MANAGED.remove(p.asLong()); }
}
