package net.minecraft.core;
public class BlockPos {
    private static final int X_LEN = 26, Z_LEN = 26, Y_LEN = 12;
    private static final long X_MASK = (1L << X_LEN) - 1, Y_MASK = (1L << Y_LEN) - 1, Z_MASK = (1L << Z_LEN) - 1;
    private static final int Z_OFF = Y_LEN, X_OFF = Y_LEN + Z_LEN;
    protected int x, y, z;
    public BlockPos(int x, int y, int z) { harness.C.blockPosAlloc++; this.x = x; this.y = y; this.z = z; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public BlockPos below() { return new BlockPos(x, y - 1, z); }
    public BlockPos north() { return new BlockPos(x, y, z - 1); }
    public BlockPos south() { return new BlockPos(x, y, z + 1); }
    public BlockPos east()  { return new BlockPos(x + 1, y, z); }
    public BlockPos west()  { return new BlockPos(x - 1, y, z); }
    public BlockPos immutable() { return this; }
    public long asLong() { return asLong(x, y, z); }
    public static long asLong(int x, int y, int z) {
        return (((long) x & X_MASK) << X_OFF) | (((long) y & Y_MASK)) | (((long) z & Z_MASK) << Z_OFF);
    }
    public static int getX(long p) { return (int) (p << (64 - X_OFF - X_LEN) >> (64 - X_LEN)); }
    public static int getY(long p) { return (int) (p << (64 - Y_LEN) >> (64 - Y_LEN)); }
    public static int getZ(long p) { return (int) (p << (64 - Z_OFF - Z_LEN) >> (64 - Z_LEN)); }
    public static BlockPos of(long p) { return new BlockPos(getX(p), getY(p), getZ(p)); }
    @Override public String toString() { return x + "," + y + "," + z; }
    public static class MutableBlockPos extends BlockPos {
        public MutableBlockPos() { super(0, 0, 0); }
        public MutableBlockPos set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override public BlockPos immutable() { return new BlockPos(x, y, z); }
    }
}
