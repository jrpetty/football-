package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.ServiceStage;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * [shops-homes] /village service (ops, the client smoke):
 * <ul>
 * <li>{@code stage}: the shop stood up by the spot with its service counter, stocked and set out, folk queuing at it;
 *     two houses side by side, a poor household's and a well-off one's; and where each camera stands (SHOT lines).
 *     {@code stage done} lets the folk go.</li>
 * <li>{@code open <shop|cafe|tavern|bank|smithy|tailor> [buy|sell|full]}: that trade's screen on the player who runs it,
 *     at its nearest point (full: the shop's basket filled).</li>
 * </ul>
 */
public final class ServiceCommands {

    private ServiceCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("service").requires(src -> src.hasPermission(2))
            .then(Commands.literal("stage").executes(ServiceCommands::stage)
                .then(Commands.literal("done").executes(ctx -> {
                    int n = ServiceStage.release(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(() -> Component.literal("SERVICE STAGE DONE: " + n + " let go"), false);
                    return n;
                })))
            .then(Commands.literal("open").then(Commands.argument("role", StringArgumentType.word())
                .executes(ctx -> open(ctx, "buy"))
                .then(Commands.argument("tab", StringArgumentType.word()).executes(ctx -> open(ctx, StringArgumentType.getString(ctx, "tab"))))));
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village within reach."));
            return 0;
        }
        List<String> out = ServiceStage.stage(level, v, at);
        String said = "SERVICE STAGE | " + String.join(" | ", out);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return out.size();
    }

    private static int open(CommandContext<CommandSourceStack> ctx, String tab) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ServiceRole role = ServiceRole.of(StringArgumentType.getString(ctx, "role"));
        if (role == null) {
            ctx.getSource().sendFailure(Component.literal("No such trade: shop, cafe, tavern, bank, smithy or tailor."));
            return 0;
        }
        String said = ServiceStage.open(p, role, tab);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }
}
