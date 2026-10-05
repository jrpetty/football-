package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Museum;
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
 * /village museum — the museum and its archive (entity/Museum, entity/Archive).
 *
 * <pre>
 *   /village museum           the nearest village's museum: its curator, what is on show and who found
 *                             each thing, the volumes of its chronicle and where they stand, what is
 *                             waiting, and what it is short of
 *   /village museum work      (ops) the curator's next piece of work done now, out of the stores
 *   /village museum stage     (ops) a museum set out where you stand for the pictures, its places filled
 *                             with one of everything and the chronicle bound into its archive, for the
 *                             village it stands by; says where to look from
 * </pre>
 */
public final class MuseumCommands {

    private MuseumCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("museum")
            .executes(MuseumCommands::status)
            .then(Commands.literal("work").requires(src -> src.hasPermission(2)).executes(MuseumCommands::work))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(MuseumCommands::stage));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx, int range) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, range);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough to have a museum."));
            return 0;
        }
        List<String> lines = Museum.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int work(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String did = Museum.workNow(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("MUSEUM " + Villages.name(v.id()) + ": " + did), false);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = near(ctx, 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("Stand within two hundred blocks of a village: the museum is its."));
            return 0;
        }
        List<String> views = Museum.stage(level, v, at);
        ctx.getSource().sendSuccess(() -> Component.literal("MUSEUM " + String.join(" | ", views)), false);
        return views.size();
    }
}
