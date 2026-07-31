package com.jrp.floodworld.flow;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import traben.flowing_fluids.api.FlowingFluidsAPI;

/**
 * Thin adapter over the Flowing Fluids API. Everything FloodWorld knows about partial water
 * levels goes through here.
 */
public final class FloodWorldFluids {

    private static FlowingFluidsAPI api;
    private static boolean available;

    private FloodWorldFluids() {
    }

    public static void init(String modid) {
        try {
            api = FlowingFluidsAPI.getInstance(modid);
            available = api != null;
        } catch (Throwable t) {
            api = null;
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean isModifyingWater() {
        return available && api.isModEnabled() && api.doesModifyThisFluid(Fluids.WATER);
    }

    /**
     * Water level 0-8 at {@code pos}. Prefer {@link #waterAmount(BlockState)} on hot paths: this
     * overload has to resolve the chunk for every call.
     */
    public static int getWaterAmount(LevelAccessor level, BlockPos pos) {
        return waterAmount(level.getFluidState(pos));
    }

    /**
     * Water level 0-8 for an already-resolved block state. {@code LevelChunk.getFluidState} is
     * defined as {@code getBlockState(..).getFluidState()}, so this is identical to
     * {@link #getWaterAmount} for any position whose chunk is loaded — minus the chunk lookup.
     */
    public static int waterAmount(BlockState state) {
        return waterAmount(state.getFluidState());
    }

    private static int waterAmount(FluidState fs) {
        return fs.is(FluidTags.WATER) ? fs.getAmount() : 0;
    }

    public static boolean setWaterAmount(LevelAccessor level, BlockPos pos, int amount) {
        return api.modifyFluidAmountAtPos(level, pos, Fluids.WATER, amount);
    }
}
