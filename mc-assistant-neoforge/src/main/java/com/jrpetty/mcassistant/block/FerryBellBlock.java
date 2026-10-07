package com.jrpetty.mcassistant.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [transport] The ferry bell: a little copper bell hung in a wooden frame, that stands on the bank beside each of a
 * ferry's landings (entity/Ferries). Ring it (use it) and the ferry is called over to that landing if it is on the
 * far side; the folk waiting at a landing ring it themselves when the boat is across the water. The town's own make,
 * at the bench, out of a copper ingot, a stick and planks (the recipe: data/mc_assistant/recipe/ferry_bell.json); a
 * player can make one and set it anywhere, where it is only a bell.
 */
public class FerryBellBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<FerryBellBlock> CODEC = simpleCodec(FerryBellBlock::new);

    /** The frame's two posts and its crossbar, and the bell hung between them, along the block's own way. */
    private static final VoxelShape ALONG_X = Shapes.or(
        Block.box(1, 0, 6, 3, 16, 10), Block.box(13, 0, 6, 15, 16, 10), Block.box(1, 14, 6, 15, 16, 10),
        Block.box(4, 3, 4, 12, 14, 12));
    private static final VoxelShape ALONG_Z = Shapes.or(
        Block.box(6, 0, 1, 10, 16, 3), Block.box(6, 0, 13, 10, 16, 15), Block.box(6, 14, 1, 10, 16, 15),
        Block.box(4, 3, 4, 12, 14, 12));

    public FerryBellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getClockWise());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        // The model is drawn facing north, its frame across the way it faces (along x): turned a quarter for east and west.
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ALONG_X : ALONG_Z;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, net.minecraft.world.level.LevelAccessor level,
                                     BlockPos pos, BlockPos otherPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    /** Rung: the ferry called to this landing (Ferries.rung), and the ringer told what comes of it. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level instanceof ServerLevel server) {
            String said = com.jrpetty.mcassistant.entity.Ferries.rung(server, pos);
            player.displayClientMessage(Component.literal(said), true);
        }
        return InteractionResult.CONSUME;
    }
}
