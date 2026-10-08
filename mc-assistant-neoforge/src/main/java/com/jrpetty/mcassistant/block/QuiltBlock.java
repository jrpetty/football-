package com.jrpetty.mcassistant.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [leisure] A patchwork quilt laid on a bed (item/LeisureItems, entity/Quilts). It is a block of its own in the air
 * over the foot of the bed, drawn lying on the mattress below it: over the foot and half the head, down the bed's
 * sides and its foot, the pillow left showing. Facing the way the bed does (to its head). While somebody sleeps in the
 * bed (the bed says so: occupied) it is drawn tucked up over the sleeper, its head and shoulders out on the pillow.
 * It comes up with the bed: take the bed away and the quilt drops.
 */
public class QuiltBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<QuiltBlock> CODEC = simpleCodec(QuiltBlock::new);
    /** Somebody asleep under it. */
    public static final BooleanProperty TUCKED = BooleanProperty.create("tucked");
    /** Where it lies to the eye and the hand: on the mattress, seven sixteenths below this block. */
    private static final VoxelShape FLAT = Block.box(0.0, -7.0, 0.0, 16.0, -6.0, 16.0);
    private static final VoxelShape HUMP = Block.box(0.0, -7.0, 0.0, 16.0, -1.0, 16.0);

    public QuiltBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TUCKED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TUCKED);
    }

    /** The state for a quilt on this bed (its foot half's state): its way, and tucked if the bed is slept in. */
    public BlockState onBed(BlockState foot) {
        return defaultBlockState().setValue(FACING, foot.getValue(BedBlock.FACING)).setValue(TUCKED, foot.getValue(BedBlock.OCCUPIED));
    }

    /** Is this the foot of a bed a quilt can lie on? */
    public static boolean bedFoot(BlockState s) {
        return s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(TUCKED) ? HUMP : FLAT;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return bedFoot(level.getBlockState(pos.below()));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (dir != Direction.DOWN) return state;
        if (!bedFoot(other)) return Blocks.AIR.defaultBlockState();
        // The bed slept in, or left: the quilt over the sleeper, or smoothed flat again.
        return state.setValue(FACING, other.getValue(BedBlock.FACING)).setValue(TUCKED, other.getValue(BedBlock.OCCUPIED));
    }

    /** A hand on the quilt is a hand on the bed: to sleep in it. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos bed = pos.below();
        BlockState under = level.getBlockState(bed);
        if (!(under.getBlock() instanceof BedBlock)) return InteractionResult.PASS;
        return under.useWithoutItem(level, player, hit.withPosition(bed));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return true;
    }
}
