package dev.structint.world;
import it.unimi.dsi.fastutil.longs.*;
public final class ManagedBlocks {
    private final LongOpenHashSet p=new LongOpenHashSet();
    public void add(long k){p.add(k);} public boolean isEmpty(){return p.isEmpty();} public LongSet view(){return p;}
}
