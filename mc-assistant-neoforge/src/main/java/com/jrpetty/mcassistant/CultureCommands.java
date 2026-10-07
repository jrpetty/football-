package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Theatre;
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
 * /village culture [batchD] — the town's culture (entity/Culture): its banner and motto, its customs, the
 * theatre and its plays, the band and the choir, its pictures and its plaques.
 *
 * <pre>
 *   /village culture          the nearest village's: the banner in words and where it hangs, the motto, each
 *                             custom and its next day, the theatre's plays, the band, the choir, the pictures
 *                             and where they hang, the plaques and where they stand
 *   /village culture now      (ops) the banner, the motto, the shop's sign, the pictures and the plaques: every
 *                             piece the stores run to put up now
 *   /village culture play     (ops) tonight's play begun now in the nearest village with a theatre, whatever the
 *                             day: its players cast and its title
 *   /village culture stage    (ops) a theatre set out where you stand for the pictures: the town's banners on its
 *                             back wall, players on the stage, an audience on the benches; says where to look from
 * </pre>
 */
public final class CultureCommands {

    private CultureCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("culture")
            .executes(CultureCommands::status)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(CultureCommands::now))
            .then(Commands.literal("play").requires(src -> src.hasPermission(2)).executes(CultureCommands::play))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(CultureCommands::stage));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx, int range) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, range);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        List<String> lines = Culture.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        String did = Heraldry.putForTests(level, v);
        int plaques = com.jrpetty.mcassistant.entity.Plaques.putForTests(level, v).size();
        ctx.getSource().sendSuccess(() -> Component.literal("CULTURE " + Villages.name(v.id()) + ": " + (did.isEmpty() ? "nothing more to put up" : did)
            + "; plaques " + plaques), false);
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String title = Theatre.beginForTests(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(title == null
            ? "No play: the town wants two players (folk who love reading, music or whittling) free to put one on."
            : "PLAY “" + title + "” with " + String.join(", ", Theatre.castForTests(v.id()))), false);
        return title == null ? 0 : 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = near(ctx, 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("Stand within two hundred blocks of a village: the theatre is its."));
            return 0;
        }
        List<String> views = Theatre.stage(level, v, at);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", views)), false);
        return views.size();
    }
}
