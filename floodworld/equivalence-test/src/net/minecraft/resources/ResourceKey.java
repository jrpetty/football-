package net.minecraft.resources;
public class ResourceKey<T> {
    private final String id;
    public ResourceKey(String id) { this.id = id; }
    public ResourceLocation location() { return new ResourceLocation(id); }
    @Override public boolean equals(Object o) { return o instanceof ResourceKey && ((ResourceKey<?>) o).id.equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return id; }
}
