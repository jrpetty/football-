package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
        return cutBack(level, anchor, new int[]{ anchor.getX() - wx, anchor.getX() + wx, anchor.getZ() - wz, anchor.getZ() + wz });
    }

    /** [house34b] As cutBack, round this footing in the world ({minX, maxX, minZ, maxZ}: BuildGoal.box). */
    public static java.util.List<BlockPos> cutBack(Level level, BlockPos anchor, int[] box) {
        int floor = anchor.getY();                                   // the first cell of air over the floor
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = box[0] - CUT_RINGS; x <= box[1] + CUT_RINGS; x++) {
            for (int z = box[2] - CUT_RINGS; z <= box[3] + CUT_RINGS; z++) {
                int ring = Math.max(Math.max(Math.max(box[0] - x, x - box[1]), Math.max(box[2] - z, z - box[3])), 0);
                if (ring == 0) continue;                             // the footing itself: the builders' own (groundInside)
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

    /**
     * The town's own paving or a worn path underfoot (cobble, stone bricks, a path's earth): ground a wall may stand on,
     * as it stands on turf. Never a block a player set down.
     */
    public static boolean townPaving(Level level, BlockPos pos, BlockState st) {
        boolean paving = st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.STONE_BRICKS)
            || st.is(Blocks.MOSSY_STONE_BRICKS) || st.is(Blocks.CRACKED_STONE_BRICKS) || st.is(Blocks.DIRT_PATH)
            || st.is(Blocks.SMOOTH_STONE) || st.is(Blocks.POLISHED_ANDESITE) || st.is(Blocks.GRAVEL);
        return paving && !(level instanceof ServerLevel sl && PlayerBlocks.placed(sl, pos));
    }

    // ------------------------------------------------------------------ the founding's levelling and the work chests

    /**
     * A town's own work chest (a "Village Store" chest or barrel a hand set down on its plot), standing on ground the
     * founding has still to level. The levelling took any block that was not the world's own for somebody's building
     * and left the whole column standing as it was: on a mountainside the miners had set their chests on the hill
     * before the cut reached them, and five columns of rock stood up out of the levelled bowl, each with a chest on
     * top and the felled trees' crowns hanging over it. Such a chest is carried down with the ground now (Terraform).
     */
    public static boolean townChest(net.minecraft.world.level.chunk.LevelChunk chunk, BlockPos pos, BlockState st) {
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
                && !(st.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)) return false;
        if (chunk.getLevel() instanceof ServerLevel sl && PlayerBlocks.placed(sl, pos)) return false;
        net.minecraft.world.level.block.entity.BlockEntity be = chunk.getBlockEntity(pos);
        return be != null && ZoneChests.isVillageStore(be);
    }

    /**
     * What a step of the founding's sloped edge is dressed in where it is cut into the hill. It kept whatever the column
     * had been, and on a mountain that was bare rock or a drift of snow: the cut stood round the town as a raw stone
     * bowl, an amphitheatre of white steps. A cut step is turfed now in the town's own soil (grass where the town's
     * ground is snow or rock), with earth under it, so the hill comes down to the town in green terraces; a step built
     * up, or one the cut never reached, keeps its own.
     */
    public static Block edgeTop(Block own, Block townSurface, boolean cut) {
        if (!cut || !(own == Blocks.STONE || own == Blocks.SNOW_BLOCK || own == Blocks.GRAVEL)) return own;
        return townSurface == Blocks.STONE || townSurface == Blocks.SNOW_BLOCK || townSurface == Blocks.GRAVEL
            ? Blocks.GRASS_BLOCK : townSurface;
    }

    /** A work chest lifted out of a column (its block and what it holds), to be set down again on the new ground. */
    public record Carried(BlockState state, net.minecraft.nbt.CompoundTag tag) {}

    /** Lift a town's work chest out of the world, what is in it kept with it (nothing spills). */
    public static Carried lift(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        net.minecraft.nbt.CompoundTag tag = be == null ? new net.minecraft.nbt.CompoundTag() : be.saveWithoutMetadata(level.registryAccess());
        if (be instanceof net.minecraft.world.Container c) c.clearContent();           // so nothing drops as it goes
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new Carried(st, tag);
    }

    /** Set a lifted work chest down at the first free cell at or over {@code at}, its goods back in it. */
    public static void setDown(ServerLevel level, BlockPos at, Carried c) {
        BlockPos p = at;
        for (int i = 0; i < 4 && !level.getBlockState(p).canBeReplaced(); i++) p = p.above();
        BlockState st = c.state();
        if (st.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
            st = st.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.SINGLE);
        }
        level.setBlock(p, st, Block.UPDATE_ALL);
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
        if (be != null) {
            be.loadWithComponents(c.tag(), level.registryAccess());
            be.setChanged();
        }
    }

    // ------------------------------------------------------------------ the square's wall on its ground

    /** The square's wall's own stuff (its masonry, cobble, coping slabs, lanterns and torches): a column of it is the wall
     *  already, not ground to build another on. */
    public static boolean wallStuff(BlockState st) {
        return st.is(BlockTags.STONE_BRICKS) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE)
            || st.is(Blocks.STONE_BRICK_SLAB) || st.is(Blocks.COBBLESTONE_SLAB) || st.is(Blocks.MOSSY_STONE_BRICK_SLAB)
            || st.is(Blocks.SMOOTH_STONE_SLAB) || st.is(Blocks.STONE_SLAB) || st.is(Blocks.DEEPSLATE_BRICKS)
            || st.is(Blocks.DEEPSLATE_TILES) || st.is(Blocks.POLISHED_DEEPSLATE) || st.is(Blocks.COBBLED_DEEPSLATE)
            || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.BRICKS);
    }

    /**
     * The ground a column of the square's wall stands on: the first cell over the world's own earth and rock, down through
     * the wall's own courses, the town's paving and what grows (at most a dozen down). The wall used to be stood on the
     * top of whatever was there, and a wall it had begun before was the top: each time its builders came back to finish
     * it, its columns went up on top of the courses already laid, and the square's wall grew into towers forty high.
     * Water is the top of a column (its wall stands on a footing up from the bed: BuildGoal.wetBed); anything else built
     * stops the look where it is, so the gate and the fit see it.
     */
    public static int wallGround(Level level, int x, int z) {
        int top = com.jrpetty.mcassistant.entity.goal.BuildGoal.groundTop(level, x, z);
        int y = top;
        for (int i = 0; i < 12; i++) {
            BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
            if (!below.getFluidState().isEmpty()) return y;
            if (Terraform.earth(below) && !townPaving(level, new BlockPos(x, y - 1, z), below)) return y;
            if (below.isAir() || below.canBeReplaced() || below.is(BlockTags.LEAVES) || wallStuff(below)
                    || townPaving(level, new BlockPos(x, y - 1, z), below)) { y--; continue; }
            return top;                                              // somebody's building: as it stands
        }
        return top;
    }

    /** May the square's wall stand on this column, on its ground {@code g}: open ground, the town's paving, or its own
     *  courses already laid there; never a field, a floor, water or somebody's building. */
    public static boolean wallFits(Level level, int x, int z, int g) {
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos at = new BlockPos(x, g - 1, z);
        BlockState ground = level.getBlockState(at);
        if (!ground.getFluidState().isEmpty() || ground.is(Blocks.FARMLAND)) return false;
        if (!Terraform.earth(ground) && !townPaving(level, at, ground) && !wallStuff(ground)) return false;
        for (int dy = 0; dy <= 4; dy++) {
            BlockPos c = new BlockPos(x, g + dy, z);
            BlockState st = level.getBlockState(c);
            if (st.isAir() || wallStuff(st) && !(level instanceof ServerLevel sl && PlayerBlocks.placed(sl, c))) continue;
            if (st.is(BlockTags.LEAVES) && !(st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT))) continue;
            if (st.canBeReplaced() && st.getFluidState().isEmpty() || litter(st)) continue;
            return false;
        }
        return true;
    }

    /** As {@link #litter(BlockState)}, or a torch stuck in the ground; and nobody's: never a block a player set down. */
    public static boolean litter(Level level, BlockPos pos, BlockState st) {
        if (!litter(st) && !groundTorch(level, pos, st)) return false;
        return !(level instanceof ServerLevel sl) || !PlayerBlocks.placed(sl, pos);
    }
}
