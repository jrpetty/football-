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

    /** How far out from its walls a building cuts the hillside back. */
    public static final int CUT_RINGS = 4;
    /** How high over its floor a bank is cut away, at most. */
    public static final int CUT_HIGH = 10;

    /**
     * The hillside cut back from a building's walls: a building set into a slope (its floor at the lot's high side, or
     * the middle of a gentle one) had the bank standing against its uphill wall, earth to the windows. The ground round
     * it is taken down to its floor in the first ring out from the footing, a block higher in the next, and so on (one
     * up for one out, as a bank stands) until it meets the hillside, a few rings out at most. Only the world's own
     * earth and rock, a column at a time from its top, and never under anything built (the column stops at the first
     * block that is not ground) nor through a tree. Returned top first, for the builders' ground work to dig
     * (BuildGoal.addTerrainWork, as a park's ground is dug).
     *
     * @param hw half the footing across and @param hd half its depth (the drawing's own, before it is turned)
     */
    public static java.util.List<BlockPos> cutBack(Level level, BlockPos anchor, net.minecraft.core.Direction facing, int hw, int hd) {
        boolean turned = facing.getAxis() == net.minecraft.core.Direction.Axis.X;
        int wx = turned ? hd : hw, wz = turned ? hw : hd;
        int floor = anchor.getY();                                   // the first cell of air over the floor
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -wx - CUT_RINGS; dx <= wx + CUT_RINGS; dx++) {
            for (int dz = -wz - CUT_RINGS; dz <= wz + CUT_RINGS; dz++) {
                int ring = Math.max(Math.max(Math.abs(dx) - wx, Math.abs(dz) - wz), 0);
                if (ring == 0) continue;                             // the footing itself: the builders' own (groundInside)
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int keep = floor + ring - 1;                         // the highest the ground may stand here: its top cell below this
                for (int y = floor + CUT_HIGH; y >= keep; y--) {
                    BlockPos c = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(c);
                    if (st.isAir() || litter(level, c, st)) continue;
                    if (!Terraform.earth(st) || st.is(Blocks.BEDROCK)
                            || level instanceof ServerLevel sl && PlayerBlocks.placed(sl, c)) {
                        if (y > keep && (st.canBeReplaced() || st.is(BlockTags.LEAVES))) continue;   // grass, a tree's crown over it
                        break;                                       // a log, a wall, a fence, water: not this column
                    }
                    out.add(c);
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt((BlockPos p) -> -p.getY()));
        return out;
    }

    /** As {@link #litter(BlockState)}, or a torch stuck in the ground; and nobody's: never a block a player set down. */
    public static boolean litter(Level level, BlockPos pos, BlockState st) {
        if (!litter(st) && !groundTorch(level, pos, st)) return false;
        return !(level instanceof ServerLevel sl) || !PlayerBlocks.placed(sl, pos);
    }
}
