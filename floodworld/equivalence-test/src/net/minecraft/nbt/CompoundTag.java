package net.minecraft.nbt;
import java.util.HashMap;
import java.util.Map;
public class CompoundTag implements Tag {
    private final Map<String, Object> m = new HashMap<>();
    public boolean contains(String k) { return m.containsKey(k); }
    public CompoundTag getCompound(String k) { Object o = m.get(k); return o instanceof CompoundTag ? (CompoundTag) o : new CompoundTag(); }
    public long[] getLongArray(String k) { Object o = m.get(k); return o instanceof long[] ? (long[]) o : new long[0]; }
    public int[] getIntArray(String k) { Object o = m.get(k); return o instanceof int[] ? (int[]) o : new int[0]; }
    public void putLongArray(String k, long[] v) { m.put(k, v); }
    public void putIntArray(String k, int[] v) { m.put(k, v); }
    public Tag put(String k, Tag v) { return (Tag) m.put(k, v); }
}
