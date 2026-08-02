package net.minecraft.world.level;
import net.minecraft.core.BlockPos; import net.minecraft.sounds.*; import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState; import net.minecraft.world.level.material.FluidState;
public abstract class Level implements LevelAccessor, BlockGetter {
    public BlockState getBlockState(BlockPos p){ return new BlockState(harness.W.at(p.getX(),p.getY(),p.getZ())); }
    public FluidState getFluidState(BlockPos p){ return getBlockState(p).getFluidState(); }
    public boolean isOutsideBuildHeight(int y){ return y<harness.W.minY||y>=harness.W.maxY; }
    public boolean destroyBlock(BlockPos p,boolean d){
        harness.W.BROKEN.add(p.getX()+","+p.getY()+","+p.getZ());
        harness.W.B.put(harness.W.k(p.getX(),p.getY(),p.getZ()),"air");
        harness.W.MANAGED.remove(harness.W.k(p.getX(),p.getY(),p.getZ()));
        return true; }
    public void playSound(Player pl,BlockPos p,SoundEvent s,SoundSource src,float v,float pi){}
    public long getGameTime(){ return harness.W.BROKEN.size(); }
}
