package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [workitems] Thatch: wheat straw combed and bound in courses, the Wood Age's roof (entity/Thatch). It burns as readily
 * as hay does (the same catch and spread), so a spark on a thatched roof is a fire; and, like a bale, it breaks a fall.
 * Its stairs and its slab are cut from it as any block's are.
 */
public class ThatchBlock extends Block {

    /** As a hay bale: it catches at once (the fire's odds of spreading to it), and burns away at a fifth of that. */
    static final int CATCH = 60, BURN = 20;

    public ThatchBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        entity.causeFallDamage(fallDistance, 0.2F, level.damageSources().fall());
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return BURN;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return CATCH;
    }

    /** Thatch stairs: the slope of a thatched roof. */
    public static class Stairs extends StairBlock {
        public Stairs(BlockState base, Properties properties) {
            super(base, properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
            return BURN;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
            return CATCH;
        }
    }

    /** A thatch slab: the ridge and the eaves. */
    public static class Slab extends SlabBlock {
        public Slab(Properties properties) {
            super(properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
            return BURN;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
            return CATCH;
        }
    }
}
