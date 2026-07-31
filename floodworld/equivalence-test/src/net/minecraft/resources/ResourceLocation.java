package net.minecraft.resources;
public class ResourceLocation {
    public final String id;
    public ResourceLocation(String id) { this.id = id; }
    public static ResourceLocation fromNamespaceAndPath(String ns, String path) { return new ResourceLocation(ns + ":" + path); }
    @Override public String toString() { return id; }
}
