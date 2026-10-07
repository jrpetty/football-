package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Arms;
import com.jrpetty.mcassistant.entity.Buskers;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * /village arms and /village busk [arms] — the town's arms everywhere (entity/Arms) and its street musicians
 * (entity/Buskers).
 *
 * <pre>
 *   /village arms          the nearest village's arms in words, every place they fly and whether they are up, the
 *                          watch's shields, the road, the festival tabards, the grants, what they wait on
 *   /village arms now      (ops) every piece the stores run to, now: the banners, the shields, the tabards
 *   /village arms board    where to stand to see the arms in the board's header (VIEW board ...)
 *   /village arms stage    (ops) the pictures' lineup where you stand: a guard with the arms on its shield, a carrier
 *                          with the banner, two in the festival tabard, the banner on its pole (VIEW arms-lineup ...)
 *   /village busk          the town's buskers: their pitches, how good they are, their hats, who plays the tavern
 *   /village busk now      (ops) every musician out to busk at its pitch now, a few passers-by stopped round the first
 *                          (VIEW busker ...)
 * </pre>
 */
public final class ArmsCommands {

    private ArmsCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> arms() {
        return Commands.literal("arms")
            .executes(ArmsCommands::status)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ArmsCommands::now))
            .then(Commands.literal("board").executes(ArmsCommands::board))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ArmsCommands::stage));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> busk() {
        return Commands.literal("busk")
            .executes(ArmsCommands::buskers)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ArmsCommands::buskNow));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx, int range) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, range);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        return v == null ? 0 : say(ctx, Arms.status(ctx.getSource().getLevel(), v));
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        return say(ctx, List.of("ARMS " + Villages.name(v.id()) + ": " + Arms.now(level, v)));
    }

    private static int board(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        return v == null ? 0 : say(ctx, List.of(Arms.boardView(v.id())));
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, 200);
        if (v == null) return 0;
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return say(ctx, Arms.stage(ctx.getSource().getLevel(), v, at));
    }

    private static int buskers(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        return v == null ? 0 : say(ctx, Buskers.status(ctx.getSource().getLevel(), v));
    }

    private static int buskNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        return v == null ? 0 : say(ctx, Buskers.now(ctx.getSource().getLevel(), v));
    }
}
