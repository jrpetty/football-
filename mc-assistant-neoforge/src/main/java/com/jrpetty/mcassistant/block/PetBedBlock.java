package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [pets] A pet's own bed (entity/Pets): the dog bed, a padded cushion in a low rim that a household sets by its
 * door, and the cat basket, a wicker basket with a cushion in it set by the hearth or under a window. The
 * household's pet sleeps in it of a night. Right-click it to see whose it is.
 */
public class PetBedBlock extends Block {

    private final boolean basket;
    private final VoxelShape shape;

    public PetBedBlock(Properties properties, boolean basket) {
        super(properties);
        this.basket = basket;
        this.shape = basket ? Block.box(2.0, 0.0, 2.0, 14.0, 5.0, 14.0) : Block.box(1.0, 0.0, 1.0, 15.0, 4.0, 15.0);
    }

    /** The cat's basket, rather than the dog's bed. */
    public boolean basket() {
        return basket;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shape;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos under = pos.below();
        return level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        String whose = com.jrpetty.mcassistant.entity.Pets.whoseBed(level, pos);
        player.displayClientMessage(Component.literal(whose.isEmpty() ? (basket ? "A cat basket, with a cushion in it." : "A dog bed, nicely padded.")
            : whose), true);
        return InteractionResult.CONSUME;
    }
}
