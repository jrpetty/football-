package com.jrpetty.mcassistant.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [featcivic] The ballot box: a dark wooden box on short legs, a slot in its lid and a white card on its front. The town
 * sets one out before its board or hall as its polling station when an election is called (entity/Polls); folk queue at
 * it and drop their ballots in. A citizen uses it to vote (the ballot screen: entity/GovActions); anybody uses it to see
 * the town's government, its election and what a player may do there. Made of six planks round a sheet of paper; a box
 * set down by hand in a town is that town's polling station from then on.
 */
public class BallotBoxBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<BallotBoxBlock> CODEC = simpleCodec(BallotBoxBlock::new);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    public BallotBoxBlock(Properties properties) {
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
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /** Not a way through: folk queue before it, not into it. */
    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level instanceof ServerLevel server && by instanceof Player) com.jrpetty.mcassistant.entity.Polls.placedByHand(server, pos);
    }

    /** Used: the ballot screen (or the town's government, with no election on). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) com.jrpetty.mcassistant.entity.GovActions.openBallot(sp, pos);
        return InteractionResult.CONSUME;
    }
}
