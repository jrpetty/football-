package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.entity.Town;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Job Board — the town's hiring desk. Place one down and right-click it to
 * cycle the crew's role preset:
 *
 *   AUTO → MINING → BALANCED → FOOD → BUILD → AUTO ...
 *
 * Right-clicking also registers this board as your town's center, so your
 * autonomous crew self-assigns roles around it: with AUTO they balance to
 * whatever the depot chest is short on (food/wood/stone/iron); with a fixed
 * preset they bias toward that trade. One board per player is the active hub
 * (right-click a different one to move the town).
 */
public class JobBoardBlock extends Block {

    public enum Preset implements StringRepresentable {
        AUTO("auto"), MINING("mining"), BALANCED("balanced"), FOOD("food"), BUILD("build");

        private final String id;
        Preset(String id) { this.id = id; }

        @Override
        public String getSerializedName() { return id; }

        public Preset next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public static final EnumProperty<Preset> PRESET = EnumProperty.create("preset", Preset.class);

    public JobBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PRESET, Preset.AUTO));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PRESET);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Preset next = state.getValue(PRESET).next();
        level.setBlock(pos, state.setValue(PRESET, next), 3);
        Town.setCenter(player.getUUID(), pos);
        player.displayClientMessage(Component.literal(
            "Job Board — crew preset: " + next.getSerializedName()
                + (next == Preset.AUTO
                    ? " (self-balancing to what the depot needs)"
                    : " (crew biased to " + next.getSerializedName() + ")")), true);
        return InteractionResult.CONSUME;
    }
}
