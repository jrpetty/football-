package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [crime] The stocks: a low bench behind a hinged board with two holes in it, for the feet of whoever the council
 * sentences to sit in them on the square for a day (entity/Trial). FACING is the way the one sat in them looks: the
 * board and its two posts across the front of the block, the bench along the back. Made of three planks over two logs,
 * by anybody, and put up by the town out of its stores the first time a sentence wants them.
 */
public class StocksBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Facing north: the board at the north edge (z 3 to 6), the bench from z 8 to 15, half a block high.
    private static final VoxelShape NORTH = Shapes.or(Block.box(0, 0, 3, 16, 13, 6), Block.box(1, 0, 8, 15, 8, 15));
    private static final VoxelShape SOUTH = Shapes.or(Block.box(0, 0, 10, 16, 13, 13), Block.box(1, 0, 1, 15, 8, 8));
    private static final VoxelShape EAST = Shapes.or(Block.box(10, 0, 0, 13, 13, 16), Block.box(1, 0, 1, 8, 8, 15));
    private static final VoxelShape WEST = Shapes.or(Block.box(3, 0, 0, 6, 13, 16), Block.box(8, 0, 1, 15, 8, 15));

    public StocksBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Set down facing away from whoever puts it up, as a bench is. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
