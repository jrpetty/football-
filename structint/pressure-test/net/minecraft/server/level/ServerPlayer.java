package net.minecraft.server.level;
import net.minecraft.core.BlockPos; import net.minecraft.world.level.ChunkPos;
public class ServerPlayer { public BlockPos pos=new BlockPos(0,70,0);
  public ChunkPos chunkPosition(){return new ChunkPos(pos.getX()>>4,pos.getZ()>>4);}
  public BlockPos blockPosition(){return pos;} }
