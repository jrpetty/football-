package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * [build5] A field fenced. A town's fields were squares of furrows laid on its farmland's grid (Villages.fieldSquares),
 * a water hole in each nine-by-nine (FarmGoal, FieldWater), open on every side: the cows and the night's monsters walked
 * in and trampled them, and from the air they were patches, not fields. On its rounds (Fields, once a minute) the farmer
 * fences its field a few posts at a time out of the stores: a fence round its edge, one block out from the furrows, on
 * the ground as it goes, with a gate in the middle of the side that faces the town (and the field's torches set on the
 * posts). Never across a road or anything built (the town's gate: TownBlocks, Why.FIELD), never on another field's
 * furrows or water. When the field grows, the old fence inside it comes up again into the stores.
 */
public final class FieldEdge {

    private FieldEdge() {}

    /** Posts set at one visit, at most. */
    static final int POSTS = 12;

    /** Fence a farmer's field a little more. Returns the blocks set or taken up. */
    public static int keep(ServerLevel level, Villages.Village v, VillageFolkEntity f, WorkZone z) {
        BlockPos c = z.center();
        int r = z.radius(), edge = r + 1, n = 0;
        // An old fence inside the field (it has grown) comes up.
        for (int dx = -r; dx <= r && n < POSTS; dx++) {
            for (int dz = -r; dz <= r && n < POSTS; dz++) n += takeUp(level, v, c.getX() + dx, c.getZ() + dz);
        }
        // The gate: the middle of the side facing the town's heart.
        int hx = v.centre().getX() - c.getX(), hz = v.centre().getZ() - c.getZ();
        int gx, gz;
        if (Math.abs(hx) >= Math.abs(hz)) { gx = Integer.signum(hx) * edge; gz = 0; } else { gx = 0; gz = Integer.signum(hz) * edge; }
        for (int dx = -edge; dx <= edge && n < POSTS; dx++) {
            for (int dz = -edge; dz <= edge && n < POSTS; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != edge) continue;
                boolean gate = dx == gx && dz == gz;
                n += post(level, v, f, c.getX() + dx, c.getZ() + dz, gate, gate ? (gz != 0 ? Direction.SOUTH : Direction.EAST) : null);
            }
        }
        return n;
    }

    /** One post (or the gate) on the ground here, if it may stand. */
    static int post(ServerLevel level, Villages.Village v, VillageFolkEntity f, int x, int z, boolean gate, Direction facing) {
        if (!level.hasChunk(x >> 4, z >> 4)) return 0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos at = new BlockPos(x, y, z);
        BlockState here = level.getBlockState(at.below());
        // Already a post here (or the field's torch on one): done.
        if (here.is(BlockTags.FENCES) || here.getBlock() instanceof FenceGateBlock) return 0;
        BlockState torch = null;
        if (here.is(Blocks.TORCH) && !PlayerBlocks.placed(level, at.below())) {       // the field's torch: onto the post
            torch = here;
            at = at.below();
            here = level.getBlockState(at.below());
        }
        BlockState cell = level.getBlockState(at);
        if (!(cell.isAir() || torch != null || cell.canBeReplaced() && cell.getFluidState().isEmpty())) return 0;
        if (!here.isSolid() || here.getBlock() instanceof FarmBlock || here.is(Blocks.DIRT_PATH) || !here.getFluidState().isEmpty()) return 0;
        if (!Terraform.earth(here)) return 0;                                           // somebody's floor, a street's paving
        if (!TownBlocks.mayPlace(level, v, at, TownBlocks.Why.FIELD)) return 0;
        ItemStack[] got = new ItemStack[1];
        boolean have = Crafts.take(level, v, s -> {
            boolean ok = (gate ? s.is(ItemTags.FENCE_GATES) : s.is(ItemTags.WOODEN_FENCES)) && s.getItem() instanceof BlockItem;
            if (ok && got[0] == null) got[0] = s.copyWithCount(1);
            return ok;
        }, 1);
        if (!have || got[0] == null) return 0;
        Block b = ((BlockItem) got[0].getItem()).getBlock();
        BlockState st = b.defaultBlockState();
        if (gate && st.hasProperty(FenceGateBlock.FACING)) st = st.setValue(FenceGateBlock.FACING, facing);
        if (torch != null) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlockAndUpdate(at, st);
        if (torch != null && !gate) level.setBlockAndUpdate(at.above(), Blocks.TORCH.defaultBlockState());
        else if (torch != null) Market.intoStores(level, v.id(), new ItemStack(net.minecraft.world.item.Items.TORCH));
        return 1;
    }

    /** A post (or gate) of the town's own standing inside a field: up, into the stores. */
    static int takeUp(ServerLevel level, Villages.Village v, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return 0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos at = new BlockPos(x, y, z);
        BlockState st = level.getBlockState(at);
        if (st.is(Blocks.TORCH)) { at = at.below(); st = level.getBlockState(at); }
        if (!(st.is(BlockTags.WOODEN_FENCES) || st.getBlock() instanceof FenceGateBlock) || PlayerBlocks.placed(level, at)) return 0;
        if (!(level.getBlockState(at.below()).getBlock() instanceof FarmBlock) && !Terraform.earth(level.getBlockState(at.below()))) return 0;
        // Only the field's own fence: one with furrows beside it.
        boolean furrows = false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(at.below().relative(d)).getBlock() instanceof FarmBlock) furrows = true;
        if (!furrows) return 0;
        BlockState over = level.getBlockState(at.above());
        if (over.is(Blocks.TORCH)) level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
        Market.intoStores(level, v.id(), new ItemStack(st.getBlock().asItem()));
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        return 1;
    }
}
