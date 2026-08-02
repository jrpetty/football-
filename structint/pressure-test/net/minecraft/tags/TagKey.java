package net.minecraft.tags;
import net.minecraft.resources.ResourceKey; import net.minecraft.resources.ResourceLocation;
@SuppressWarnings("rawtypes") public class TagKey<T> { public final String id; public TagKey(String i){id=i;}
  public static TagKey create(ResourceKey r, ResourceLocation l){return new TagKey(l.id);} }
