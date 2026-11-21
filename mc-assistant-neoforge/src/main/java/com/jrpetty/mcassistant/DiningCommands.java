package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Dining;
import com.jrpetty.mcassistant.entity.DiningStage;
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
 * [dining] /village dining — eating out (entity/Dining, Diners, Hospitality).
 *
 * <pre>
 *   /village dining                      the nearest town's places to eat: tables, orders in, takings, and its staff
 *   /village dining stage                (ops) the evening's service as a scene for the pictures (DiningStage)
 *   /village dining stage menu           (ops) the order screen at a free table, for whoever runs it
 *   /village dining stage done           (ops) the diners home
 * </pre>
 */
public final class DiningCommands {

    private DiningCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> stage = Commands.literal("stage").requires(src -> src.hasPermission(2))
            .executes(ctx -> stage(ctx, "on"));
        for (String s : new String[]{ "menu", "done" }) stage.then(Commands.literal(s).executes(ctx -> stage(ctx, s)));
        return Commands.literal("dining").executes(DiningCommands::show).then(stage);
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        return say(ctx, Dining.lines(level, v.id()));
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, String which) {
        var p = ctx.getSource().getPlayer();
        if (which.equals("menu") && p == null) {
            ctx.getSource().sendFailure(Component.literal("The menu is opened for a player."));
            return 0;
        }
        return say(ctx, DiningStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), which, p));
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }
}
