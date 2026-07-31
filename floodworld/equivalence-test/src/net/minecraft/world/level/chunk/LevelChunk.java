package net.minecraft.world.level.chunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public class LevelChunk extends ChunkAccess {
    public LevelChunk(int cx, int cz) { this.cx = cx; this.cz = cz; }
    public BlockState getBlockState(BlockPos pos) { harness.C.chunkGetBlockState++; return harness.Blocks.at(pos.getX(), pos.getY(), pos.getZ()); }
}
