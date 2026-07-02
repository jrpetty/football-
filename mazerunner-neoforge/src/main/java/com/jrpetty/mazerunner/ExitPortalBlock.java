package com.jrpetty.mazerunner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The escape portal. Seven exist around the maze perimeter; only the active
 * layout's portal is lit. It has no collision — running into it while active
 * triggers the escape/win.
 */
public class ExitPortalBlock extends Block {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public ExitPortalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level instanceof ServerLevel serverLevel && state.getValue(ACTIVE)
            && entity instanceof ServerPlayer player) {
            MazeRuntime.onPortalTouched(serverLevel, pos, player);
        }
    }
}
