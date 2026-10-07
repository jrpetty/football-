package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.TradeDeals;
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

import javax.annotation.Nullable;
import java.util.List;

/**
 * [econ-trade] /village trade — trade between towns (entity/TradeBook, TradeTalks, TradeDeals).
 *
 * <pre>
 *   /village trade                the nearest town's trade book: each ware held against what it keeps, over or
 *                                 short, made and gone out a day, days of cover, the land, its price here and its
 *                                 worth to the town; its deals and how they have gone; the specialising; the talks
 *   /village trade books          the town's books open at the Trade page
 *   /village trade now [town]     (ops) the nearest town's leader and the named (or nearest) neighbour's bargain at
 *                                 once, without the walk: every round, and the deal struck if there is one
 *   /village trade talk [town]    (ops) the elder sends an envoy to talk trade, now
 *   /village trade deliver        (ops) the next delivery of each of the nearest town's deals sets out now
 *   /village trade stage          (ops) for the pictures: a deal's caravan on the road to the nearest neighbour and
 *                                 an envoy from it before this town's board; says where each stands
 * </pre>
 */
public final class TradeCommands {

    private TradeCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("trade")
            .executes(TradeCommands::page)
            .then(Commands.literal("books").executes(TradeCommands::books))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> now(ctx, ""))
                .then(Commands.argument("town", StringArgumentType.greedyString())
                    .executes(ctx -> now(ctx, StringArgumentType.getString(ctx, "town")))))
            .then(Commands.literal("talk").requires(src -> src.hasPermission(2)).executes(ctx -> talk(ctx, ""))
                .then(Commands.argument("town", StringArgumentType.greedyString())
                    .executes(ctx -> talk(ctx, StringArgumentType.getString(ctx, "town")))))
            .then(Commands.literal("deliver").requires(src -> src.hasPermission(2)).executes(TradeCommands::deliver))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(TradeCommands::stage));
    }

    @Nullable
    private static Villages.Village nearest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        return v == null ? 0 : say(ctx, TradeDeals.page(ctx.getSource().getLevel(), v));
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("The books open on a player's screen."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Trade");                       // the Trade page (client/CityScreen.TABS)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int now(CommandContext<CommandSourceStack> ctx, String town) {
        Villages.Village v = nearest(ctx);
        return v == null ? 0 : say(ctx, TradeDeals.talkNow(ctx.getSource().getLevel(), v, town));
    }

    private static int talk(CommandContext<CommandSourceStack> ctx, String town) {
        Villages.Village v = nearest(ctx);
        return v == null ? 0 : say(ctx, List.of(TradeDeals.sendEnvoy(ctx.getSource().getLevel(), v, town)));
    }

    private static int deliver(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        return v == null ? 0 : say(ctx, TradeDeals.deliverNow(ctx.getSource().getLevel(), v));
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        return say(ctx, TradeDeals.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition())));
    }
}
