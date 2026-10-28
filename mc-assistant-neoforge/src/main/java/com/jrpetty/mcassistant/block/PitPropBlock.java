package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [workitems] The pit prop: a timber set two blocks high, as the miners stand them in the town's mine (entity/WorkTools,
 * entity/WorkSites): two legs on sole pieces, wedged up under a cap beam that runs across the roof. It holds the roof:
 * the gravel and sand over a propped stretch of tunnel do not come down into it. The legs stand at the tunnel's walls, so
 * whoever walks the tunnel walks between them: nothing of it is in anybody's way.
 */
public class PitPropBlock extends Block {

    /** The way the cap beam runs: across the tunnel. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    private static final VoxelShape LOWER_Z = Shapes.or(box(6, 0, 0.5, 10, 16, 4.5), box(6, 0, 11.5, 10, 16, 15.5),
        box(5, 0, 0, 11, 1.5, 5.5), box(5, 0, 10.5, 11, 1.5, 16));
    private static final VoxelShape UPPER_Z = Shapes.or(box(6, 0, 0.5, 10, 12, 4.5), box(6, 0, 11.5, 10, 12, 15.5),
        box(5, 12, 0, 11, 16, 16));
    private static final VoxelShape LOWER_X = Shapes.or(box(0.5, 0, 6, 4.5, 16, 10), box(11.5, 0, 6, 15.5, 16, 10),
        box(0, 0, 5, 5.5, 1.5, 11), box(10.5, 0, 5, 16, 1.5, 11));
    private static final VoxelShape UPPER_X = Shapes.or(box(0.5, 0, 6, 4.5, 12, 10), box(11.5, 0, 6, 15.5, 12, 10),
        box(0, 12, 5, 16, 16, 11));

    public PitPropBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.Z).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, HALF);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        return state.getValue(AXIS) == Direction.Axis.Z ? (lower ? LOWER_Z : UPPER_Z) : (lower ? LOWER_X : UPPER_X);
    }

    /** Walked through between its legs: the tunnel is not narrowed by it, nor are the miners' runs broken on it. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return true;
    }

    /** Set by hand: the beam across the way the player looks, along the tunnel; room for its upper half. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1 || !level.getBlockState(pos.above()).canBeReplaced(ctx)) return null;
        return defaultBlockState().setValue(AXIS, ctx.getHorizontalDirection().getClockWise().getAxis());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    /** Both halves of a prop, its legs on firm ground, standing at this spot. */
    public static void stand(Level level, BlockPos lower, Direction.Axis beam, Block block) {
        BlockState s = block.defaultBlockState().setValue(AXIS, beam);
        level.setBlock(lower, s.setValue(HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(lower.above(), s.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (dir.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (dir == Direction.UP)) {
            // The other half gone, this one goes with it (its legs, or the beam they held).
            return other.is(this) && other.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        if (half == DoubleBlockHalf.LOWER && dir == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }

    /** Broken from above in creative, the lower half goes without a drop (as a door's does). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState under = level.getBlockState(below);
            if (under.is(this) && under.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, below, Block.getId(under));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && !old.is(this) && level instanceof ServerLevel server) {
            com.jrpetty.mcassistant.entity.WorkSites.propSet(server, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moving) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && !now.is(this) && level instanceof ServerLevel server) {
            com.jrpetty.mcassistant.entity.WorkSites.propGone(server, pos);
        }
        super.onRemove(state, level, pos, now, moving);
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 20;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 5;
    }
}
