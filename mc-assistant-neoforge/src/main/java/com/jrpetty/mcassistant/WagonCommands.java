package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.TradeWagon;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WagonStage;
import com.jrpetty.mcassistant.entity.WagonManifest;
import com.jrpetty.mcassistant.entity.Wagons;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * [wagons] /village wagon — the trade wagons (entity/Wagons, TradeWagon).
 *
 * <pre>
 *   /village wagon                        the nearest town's wagon: what it is, where, doing what, and its manifest
 *   /village wagon stage road|loading|night   (ops) a scene for the pictures (WagonStage), with where to look from
 *   /village wagon stage done             (ops) the scene let go
 * </pre>
 */
public final class WagonCommands {

    private WagonCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> stage = Commands.literal("stage").requires(src -> src.hasPermission(2));
        for (String s : new String[]{ "road", "loading", "night", "done" }) {
            stage.then(Commands.literal(s).executes(ctx -> say(ctx, WagonStage.stage(ctx.getSource().getLevel(),
                BlockPos.containing(ctx.getSource().getPosition()), s))));
        }
        return Commands.literal("wagon").executes(WagonCommands::show).then(stage);
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        List<String> out = new ArrayList<>();
        out.add(Villages.name(v.id()) + ": " + (Wagons.owned(v.id()) == null ? "no wagon" : Wagons.owned(v.id()).label) + " — "
            + Wagons.stateForTests(level, v.id()));
        TradeWagon w = Wagons.wagonForTests(level, v.id());
        if (w != null) out.addAll(WagonManifest.text(level, w));
        return say(ctx, out);
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }
}
