package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Terraces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [city26] The retaining walls of a terraced town (village/Terraces), built as its ground is worked (Founding.shape).
 *
 * <p>Where a column of a terrace stands two or more over a terraced column beside it, its face down to that column is a
 * wall of dressed stone: stone bricks on ordinary ground (now and then a mossy or a cracked one, as an old wall has),
 * smooth sandstone on sand, deepslate bricks where the land is deepslate. The top of the wall is the terrace's own edge,
 * a coping a block wide, level with the lot or the street it holds up. Nothing anybody built is touched: only the
 * earth and the rock of the column, and the air of a fill.
 */
public final class TerraceWalls {

    private TerraceWalls() {}

    /** Sent to watching players, without the neighbours' shapes worked out again (as Terraform sets the ground). */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** The wall's stone for a town dressed in this soil, at this column (a little weathering here and there). */
    static BlockState stone(Block surface, int x, int y, int z) {
        if (surface == Blocks.SAND) return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        if (surface == Blocks.RED_SAND || surface == Blocks.TERRACOTTA) return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
        if (surface == Blocks.NETHERRACK || surface == Blocks.SOUL_SOIL || surface == Blocks.CRIMSON_NYLIUM
                || surface == Blocks.WARPED_NYLIUM) return Blocks.NETHER_BRICKS.defaultBlockState();
        long h = (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        int r = (int) Math.floorMod(h, 100L);
        if (r < 12) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (r < 18) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    /** Is this a block of a terrace's wall (for the audit and the makers-good)? */
    public static boolean isWall(BlockState s) {
        return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.MOSSY_STONE_BRICKS) || s.is(Blocks.CRACKED_STONE_BRICKS)
            || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.SMOOTH_RED_SANDSTONE) || s.is(Blocks.NETHER_BRICKS)
            || s.is(Blocks.DEEPSLATE_BRICKS);
    }

    /** Is a terrace's wall block under or beside either of these spots (the folk's floor and the way ahead), a few up or down? */
    public static boolean near(net.minecraft.world.level.Level level, BlockPos here, BlockPos ahead) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (BlockPos c : new BlockPos[]{ here, ahead }) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -4; dy <= 2; dy++) {
                        if (isWall(level.getBlockState(m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz)))) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Does this column of the plan want a wall (it stands two or more over a terraced neighbour)? */
    public static boolean wanted(FoundingPlan.Ground g, int i) {
        return Terraces.wallFoot(g, i) != Terraces.NONE;
    }

    /**
     * The wall in this column (plan index {@code i}, world {@code x, z}), from its foot to the terrace's top. Returns how
     * many blocks were set.
     */
    public static int build(ServerLevel level, FoundingPlan.Ground g, int i, int x, int z, Block surface) {
        int foot = Terraces.wallFoot(g, i);
        if (foot == Terraces.NONE) return 0;
        int top = g.target[i];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Something somebody built anywhere in the wall's height: the column is left as it is.
        for (int y = foot; y <= top; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!Terraform.natural(s) && !isWall(s)) return 0;
        }
        int n = 0;
        boolean deep = false;
        for (int y = foot; y <= top; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE)) deep = true;
            if (isWall(s)) continue;
            BlockState want = deep && !surface.defaultBlockState().is(Blocks.SAND) ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                : stone(surface, x, y, z);
            level.setBlock(m, want, QUIET);
            n++;
        }
        // Nothing left growing on the coping.
        BlockState on = level.getBlockState(m.set(x, top + 1, z));
        if (!on.isAir() && Terraform.growth(on)) level.setBlock(m, Blocks.AIR.defaultBlockState(), QUIET);
        return n;
    }
}
