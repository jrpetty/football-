package net.minecraft.world.level;
public class ChunkPos { public static long asLong(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; } }
