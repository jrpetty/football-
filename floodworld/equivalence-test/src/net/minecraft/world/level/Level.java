package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
public abstract class Level implements LevelAccessor {
    private static final Biome BIOME = new Biome();
    private static final Holder<Biome> BIOME_HOLDER = () -> BIOME;
    public LevelChunk getChunk(int cx, int cz) { harness.C.getChunk++; return new LevelChunk(cx, cz); }
    public BlockState getBlockState(BlockPos pos) { harness.C.levelGetBlockState++; return harness.Blocks.at(pos.getX(), pos.getY(), pos.getZ()); }
    public FluidState getFluidState(BlockPos pos) { harness.C.levelGetFluidState++; return harness.Blocks.at(pos.getX(), pos.getY(), pos.getZ()).getFluidState(); }
    /** Vanilla contract: chunk.getHeight(..) + 1 when loaded, else the min build height. */
    public int getHeight(Heightmap.Types type, int x, int z) {
        harness.C.levelGetHeight++;
        if (!harness.World.chunkLoaded(x >> 4, z >> 4)) return harness.World.MIN_Y;
        return harness.World.motionBlockingTop(x, z) + 1;
    }
    public boolean isLoaded(BlockPos pos) {
        harness.C.isLoaded++;
        if (pos.getY() < harness.World.MIN_Y || pos.getY() >= harness.World.MAX_Y) return false;
        return harness.World.chunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }
    public boolean canSeeSky(BlockPos pos) { harness.C.canSeeSky++; return !harness.World.sheltered(pos.getX(), pos.getZ()); }
    public boolean isOutsideBuildHeight(BlockPos pos) { return pos.getY() < harness.World.MIN_Y || pos.getY() >= harness.World.MAX_Y; }
    public Holder<Biome> getBiome(BlockPos pos) { harness.C.getBiome++; return BIOME_HOLDER; }
    public abstract boolean isRaining();
    public abstract RandomSource getRandom();
}
