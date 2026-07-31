package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Town;
import net.minecraft.ChatFormatting;
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

import java.util.List;

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
        // Right-click: the crew roster. Once specialists are spread across a
        // base, checking on them one at a time means walking to each; this is
        // the whole crew at a glance, and it says who is stuck and why.
        // Sneak-right-click still cycles the (colony) preset.
        if (!player.isShiftKeyDown()) {
            showRoster(player);
            return InteractionResult.CONSUME;
        }
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

    /** Print every one of this player's assistants: who, what job, where, how
     *  they're doing, and what they've produced today. */
    private static void showRoster(Player player) {
        List<AssistantEntity> crew = AssistantEntity.allFor(player.getUUID());
        if (crew.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Job Board — no crew yet. Craft an Assistant Spawner (8 rotten flesh around a "
                + "diamond block) and place it to hire your first specialist.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int stuck = 0;
        player.sendSystemMessage(Component.literal("— Crew roster (" + crew.size() + ") —")
            .withStyle(ChatFormatting.GOLD));
        for (AssistantEntity a : crew) {
            String status = a.clientStatus();
            boolean blocked = status.startsWith("Needs") || status.startsWith("Out of");
            if (blocked) stuck++;
            ChatFormatting colour = blocked ? ChatFormatting.RED
                : status.startsWith("Working") ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

            StringBuilder line = new StringBuilder();
            line.append(blocked ? "⚠ " : "• ");
            if (a.veteranLevel() >= 1) line.append("✦").append(a.veteranLevel()).append(' ');
            line.append(a.displayNameCap())
                .append(" — ").append(a.stationTask().title);
            if (a.level() != player.level()) {
                line.append(" · another dimension");
            } else {
                line.append(" · ").append(bearing(player, a));
            }
            line.append(" · ").append(status);
            String made = a.productionSummary(3);
            if (made != null) line.append(" · today: ").append(made);
            player.sendSystemMessage(Component.literal(line.toString()).withStyle(colour));
        }
        if (stuck > 0) {
            player.sendSystemMessage(Component.literal(
                stuck + " of them can't work — right-click that one to see what it needs.")
                .withStyle(ChatFormatting.RED));
        }
    }

    /** "42m NE" — enough to actually go and find a bot that needs something. */
    private static String bearing(Player player, AssistantEntity a) {
        double dx = a.getX() - player.getX();
        double dz = a.getZ() - player.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String ns = dz < -4 ? "N" : dz > 4 ? "S" : "";
        String ew = dx > 4 ? "E" : dx < -4 ? "W" : "";
        String dir = ns + ew;
        return dist + "m" + (dir.isEmpty() ? " away" : " " + dir);
    }
}
