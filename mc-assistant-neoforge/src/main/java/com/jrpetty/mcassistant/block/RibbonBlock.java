package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [civic] An opening ribbon: a band of red cloth strung across a doorway, a bridge's end or a road at waist height,
 * for the leader to cut when a great work is opened (BigWorks). Made of string and red dye, by the shop's workshop
 * on the town's order or there and then out of the stores; a player can string one anywhere. It runs one way or the
 * other across the block (the
 * way across the path its placer is looking along), stops nobody walking through it, and drops itself. [itemaudit] A
 * player cuts its own with shears, to open what it has built, and the folk about clap (BigWorks.cutByPlayer).
 */
public class RibbonBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    private static final VoxelShape ALONG_X = Block.box(0.0, 12.0, 7.0, 16.0, 14.0, 9.0);
    private static final VoxelShape ALONG_Z = Block.box(7.0, 12.0, 0.0, 9.0, 14.0, 16.0);

    public RibbonBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    /** Across the way the placer is looking: look north, and it runs east to west. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(AXIS, ctx.getHorizontalDirection().getClockWise().getAxis());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS) == Direction.Axis.X ? ALONG_X : ALONG_Z;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    /** [itemaudit] Shears to it: snipped, for a player's own opening; a town's ribbon is its leader's to cut. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.SHEARS)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!(level instanceof ServerLevel server)) return ItemInteractionResult.SUCCESS;
        boolean[] cut = { false };
        String said = com.jrpetty.mcassistant.entity.BigWorks.cutByPlayer(server, pos, player, cut);
        if (cut[0]) stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        player.displayClientMessage(Component.literal(said), true);
        return cut[0] ? ItemInteractionResult.CONSUME : ItemInteractionResult.FAIL;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        return state;
    }
}
