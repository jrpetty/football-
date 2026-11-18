package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [build5] A building's ground made ready: what lies or grows on the lot comes off before a block goes in, and nothing
 * of it is left standing in the rooms.
 *
 * <p>The builders took up grass, ferns, flowers and snow from inside a building's walls, and nothing else: a sapling
 * on the lot was left on the floor of the room and grew into a tree in the parlour, a mushroom stayed in the corner,
 * sugar cane by a pond stood two high in a kitchen, a torch somebody had stuck in the meadow lit a bedroom from the
 * floor. All of it is litter to a builder now: taken up into its pack before the floor goes down. Never a block a
 * player put there (PlayerBlocks), and never a crop, a hedge somebody set (leaves a player placed are persistent), or
 * a lantern: only what grew there, and torches stuck in the open ground (standing on earth, so never one lighting a
 * finished room from its boards).
 */
public final class SiteWork {

    private SiteWork() {}

    /** Something growing or lying on the ground that a building's rooms are no place for, whoever's ground it was. */
    public static boolean litter(BlockState st) {
        return com.jrpetty.mcassistant.entity.goal.BuildGoal.isWildPlant(st) || st.is(Blocks.SNOW)
            || st.is(BlockTags.SAPLINGS) || st.is(Blocks.BROWN_MUSHROOM) || st.is(Blocks.RED_MUSHROOM)
            || st.is(Blocks.SUGAR_CANE) || st.is(Blocks.VINE) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.GLOW_LICHEN)
            || st.is(Blocks.MOSS_CARPET) || st.is(Blocks.BAMBOO_SAPLING) || st.is(Blocks.SMALL_DRIPLEAF)
            || st.is(Blocks.COBWEB);
    }

    /** A torch stuck in the open ground (standing on earth): not one lighting a room from its floor or its wall. */
    public static boolean groundTorch(Level level, BlockPos pos, BlockState st) {
        return (st.is(Blocks.TORCH) || st.is(Blocks.SOUL_TORCH)) && Terraform.earth(level.getBlockState(pos.below()));
    }

    /** As {@link #litter(BlockState)}, or a torch stuck in the ground; and nobody's: never a block a player set down. */
    public static boolean litter(Level level, BlockPos pos, BlockState st) {
        if (!litter(st) && !groundTorch(level, pos, st)) return false;
        return !(level instanceof ServerLevel sl) || !PlayerBlocks.placed(sl, pos);
    }
}
