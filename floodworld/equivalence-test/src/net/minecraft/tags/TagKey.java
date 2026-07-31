package net.minecraft.tags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
@SuppressWarnings("rawtypes")
public class TagKey<T> {
    public final String id;
    public TagKey(String id) { this.id = id; }
    public static TagKey create(ResourceKey registry, ResourceLocation loc) { return new TagKey(loc.id); }
}
