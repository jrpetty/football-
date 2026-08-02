package net.minecraft.resources;
public class ResourceLocation { public final String id; ResourceLocation(String i){id=i;}
  public static ResourceLocation fromNamespaceAndPath(String n,String p){return new ResourceLocation(n+":"+p);} }
