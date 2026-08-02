package net.minecraft.world.level;
public class ChunkPos { public final int x,z; public ChunkPos(int x,int z){this.x=x;this.z=z;}
  public static long asLong(int x,int z){return (long)x&0xFFFFFFFFL|((long)z&0xFFFFFFFFL)<<32;}
  public static int getX(long k){return (int)k;} public static int getZ(long k){return (int)(k>>32);} }
