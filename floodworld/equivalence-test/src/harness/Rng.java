package harness;
public final class Rng implements net.minecraft.util.RandomSource {
    private long s;
    public Rng(long seed) { this.s = seed == 0 ? 1 : seed; }
    public void reseed(long seed) { this.s = seed == 0 ? 1 : seed; }
    @Override public int nextInt(int bound) {
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        int v = (int) ((s >>> 16) & 0x7fffffff);
        return v % bound;
    }
}
