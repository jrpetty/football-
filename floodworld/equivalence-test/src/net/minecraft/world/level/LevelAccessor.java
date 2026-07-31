package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
public interface LevelAccessor { FluidState getFluidState(BlockPos pos); }
