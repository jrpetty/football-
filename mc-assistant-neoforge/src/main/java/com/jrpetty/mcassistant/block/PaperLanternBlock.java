package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [leisure] A paper lantern, in the colour of its dye (item/LeisureItems): a round paper shade on a light frame, a cap
 * of dark wood top and bottom, lit from inside (light twelve). It stands on anything a lantern stands on, or hangs:
 * under a block, a fence, a wall or a chain as the iron lantern does, and under a line of string as well, so it can be
 * strung across a street (entity/Lanterns: the festival's lanterns across the square). A hanging one has a tassel.
 */
public class PaperLanternBlock extends Block {

    public static final BooleanProperty HANGING = BlockStateProperties.HANGING;
    private static final VoxelShape STANDING = Shapes.or(Block.box(4.5, 1.0, 4.5, 11.5, 9.0, 11.5), Block.box(6.0, 0.0, 6.0, 10.0, 10.0, 10.0));
    private static final VoxelShape HUNG = Shapes.or(Block.box(4.5, 3.0, 4.5, 11.5, 11.0, 11.5), Block.box(6.0, 1.0, 6.0, 10.0, 16.0, 10.0));

    private final DyeColor colour;

    public PaperLanternBlock(Properties properties, DyeColor colour) {
        super(properties);
        this.colour = colour;
        registerDefaultState(stateDefinition.any().setValue(HANGING, false));
    }

    public DyeColor colour() {
        return colour;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HANGING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        for (Direction d : ctx.getNearestLookingDirections()) {
            if (d.getAxis() != Direction.Axis.Y) continue;
            BlockState s = defaultBlockState().setValue(HANGING, d == Direction.UP);
            if (s.canSurvive(ctx.getLevel(), ctx.getClickedPos())) return s;
        }
        return null;
    }

    /** What holds a lantern up: a block's underside, a fence, a wall, a chain, a line of string, another lantern. */
    public static boolean holdsUp(LevelReader level, BlockPos above) {
        BlockState s = level.getBlockState(above);
        return Block.canSupportCenter(level, above, Direction.DOWN) || s.getBlock() instanceof FenceBlock || s.is(BlockTags.WALLS)
            || s.is(Blocks.CHAIN) || s.getBlock() instanceof TripWireBlock || s.getBlock() instanceof PaperLanternBlock && s.getValue(HANGING);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HANGING)) return holdsUp(level, pos.above());
        return Block.canSupportCenter(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        Direction holds = state.getValue(HANGING) ? Direction.UP : Direction.DOWN;
        return dir == holds && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(HANGING) ? HUNG : STANDING;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
