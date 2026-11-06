package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [workitems] A milestone: a short post of weathered stone set at the roadside, its lettered face to the road, saying the
 * next town each way and how far it is by the road ("ALDERTOR 120"). The road crew sets one every hundred blocks or so
 * and where roads meet (entity/Milestones). Right-click it to be told the way and the distance to each town the road leads
 * to; one a player sets beside a road is lettered for it.
 */
public class MilestoneBlock extends Block implements EntityBlock {

    /** The way its lettered face looks: to the road. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape SOUTH = Shapes.or(box(2, 0, 3, 14, 2, 13), box(3, 2, 4, 13, 12, 12), box(3.5, 12, 4.5, 12.5, 13.5, 11.5),
        box(5, 13.5, 6, 11, 14.5, 10));
    private static final VoxelShape EAST_WEST = Shapes.or(box(3, 0, 2, 13, 2, 14), box(4, 2, 3, 12, 12, 13), box(4.5, 12, 3.5, 11.5, 13.5, 12.5),
        box(6, 13.5, 5, 10, 14.5, 11));

    public MilestoneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SOUTH : EAST_WEST;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MilestoneBlockEntity(pos, state);
    }

    /** Set by a player beside a road: lettered for the road (or the nearest town), as the road crew would. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        if (level instanceof ServerLevel server) com.jrpetty.mcassistant.entity.Milestones.letter(server, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        for (String line : com.jrpetty.mcassistant.entity.Milestones.tell(level, pos)) player.displayClientMessage(Component.literal(line), false);
        return InteractionResult.CONSUME;
    }
}
