package harness;
/** Operation counters. Pure instrumentation - identical for both builds. */
public final class C {
    public static long hasChunk, getChunk, levelGetHeight, chunkGetHeight,
            levelGetBlockState, levelGetFluidState, chunkGetBlockState,
            canSeeSky, getBiome, isLoaded, blockPosAlloc, configGet;
    public static void reset() {
        hasChunk = getChunk = levelGetHeight = chunkGetHeight = 0;
        levelGetBlockState = levelGetFluidState = chunkGetBlockState = 0;
        canSeeSky = getBiome = isLoaded = blockPosAlloc = configGet = 0;
    }
    public static String report() {
        long chunkSource = hasChunk + getChunk;
        return "  chunk-source lookups (hasChunk+getChunk) : " + chunkSource + "\n"
             + "    hasChunk=" + hasChunk + " getChunk=" + getChunk + "\n"
             + "  Level.getHeight      : " + levelGetHeight + "\n"
             + "  Chunk.getHeight      : " + chunkGetHeight + "\n"
             + "  Level.getBlockState  : " + levelGetBlockState + "\n"
             + "  Level.getFluidState  : " + levelGetFluidState + "\n"
             + "  Chunk.getBlockState  : " + chunkGetBlockState + "\n"
             + "  Level.isLoaded       : " + isLoaded + "\n"
             + "  Level.canSeeSky      : " + canSeeSky + "\n"
             + "  Level.getBiome       : " + getBiome + "\n"
             + "  BlockPos allocations : " + blockPosAlloc + "\n"
             + "  Config value reads   : " + configGet;
    }
}
