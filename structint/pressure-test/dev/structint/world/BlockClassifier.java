package dev.structint.world;
import net.minecraft.core.BlockPos; import net.minecraft.world.level.BlockGetter; import net.minecraft.world.level.block.state.BlockState;
public final class BlockClassifier {
    public static boolean isLoadBearing(BlockState s, BlockGetter l, BlockPos p){ return !s.isAir() && !s.m.equals("water"); }
}
