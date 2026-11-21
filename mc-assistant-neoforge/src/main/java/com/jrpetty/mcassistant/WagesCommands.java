package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * /village wages — what every job is worth, and so what it pays (entity/JobWorth, entity/Wealth). [econ-wages]
 *
 * <pre>
 *   /village wages              the nearest town's wages page in the chat: the pay level, the living wage and the
 *                               top of the scale, the bill against what comes in, every job's worth part by part,
 *                               and everybody who works, best paid first, with why
 *   /village wages show         the same page on your screen, as the journal shows it
 *   /village wages books        the town's books open at the Jobs page (the mouse over a trade for its worth)
 *   /village wages card [n]     the card of the n-th best paid folk (the first if none), open on your screen:
 *                               its wage and why ("CARD name x y z" for the client smoke)
 *   /village wages reckon       the morning's reckoning of the pay scale now (ops; for the pictures)
 * </pre>
 */
public final class WagesCommands {

    private WagesCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("wages").executes(WagesCommands::chat)
            .then(Commands.literal("show").executes(WagesCommands::show))
            .then(Commands.literal("books").executes(WagesCommands::books))
            .then(Commands.literal("card").executes(ctx -> card(ctx, 1))
                .then(Commands.argument("rank", IntegerArgumentType.integer(1, 500))
                    .executes(ctx -> card(ctx, IntegerArgumentType.getInteger(ctx, "rank")))))
            .then(Commands.literal("reckon").requires(src -> src.hasPermission(2)).executes(WagesCommands::reckon));
    }

    @Nullable
    private static Villages.Village nearest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int chat(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        String text = Villages.name(v.id()) + " — wages\n" + Wealth.wagesPage(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("The wages page opens on a player's screen."));
            return 0;
        }
        String text = Wealth.wagesPage(ctx.getSource().getLevel(), v);
        if (text.length() > com.jrpetty.mcassistant.net.VillagePagePayload.MAX_TEXT) {
            text = text.substring(0, com.jrpetty.mcassistant.net.VillagePagePayload.MAX_TEXT - 2) + "…";
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
            new com.jrpetty.mcassistant.net.VillagePagePayload(Villages.name(v.id()) + " — wages", text));
        return 1;
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("The books open on a player's screen."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Jobs");                       // the Jobs page, at its trades (client/CityScreen)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int card(CommandContext<CommandSourceStack> ctx, int rank) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        List<VillageFolkEntity> ranked = Wealth.byWage(v.id());
        if (ranked.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Nobody in " + Villages.name(v.id()) + " is on a wage yet."));
            return 0;
        }
        VillageFolkEntity f = ranked.get(Math.min(ranked.size(), rank) - 1);
        BlockPos at = f.blockPosition();
        String line = "CARD " + f.displayNameCap() + " " + at.getX() + " " + at.getY() + " " + at.getZ() + " — " + JobWorth.cardLine(f);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        // Its card on the player's screen: the talk screen opened on it, then asked its wage, which turns the screen to
        // its About page (client/TalkScreen), the Wage line near the top.
        if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
            FolkTalk.open(f, p);
            FolkTalk.handle(f, p, TalkTopic.SAY, "What's your wage?");
        }
        return 1;
    }

    private static int reckon(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        JobWorth.Scale s = JobWorth.reckonNow(ctx.getSource().getLevel(), v);
        String text = "RECKONED " + Villages.name(v.id()) + ": " + String.join(" ", JobWorth.summary(s));
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
