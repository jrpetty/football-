package com.jrpetty.mcassistant.entity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiFunction;

/**
 * [capital] /village capital: the town's capital markets for players (CapitalBook and its parts). Every coin a player
 * pays in or is paid out is booked in the coin census (players paying in, paid out to players).
 * <ul>
 * <li>{@code /village capital}: the savings club or bank, the bourse, the land and the bonds in a few lines, and yours.</li>
 * <li>{@code /village capital books}: the city books open at the Capital page.</li>
 * <li>{@code deposit <coins>}, {@code withdraw [coins]}: at the savings club (or the bank, once it stands).</li>
 * <li>{@code borrow <coins>}, {@code repay [coins]}: a loan against what you hold with the town (half its worth), at the
 *     day's rate.</li>
 * <li>{@code shares}: the firms and their prices; {@code shares buy <firm> <n>}; {@code shares sell <firm> <n> [price in
 *     hundredths]}.</li>
 * <li>{@code land}: the titles; {@code land buy <n>}: the town's plot numbered so; {@code land collect}: what waits for you.</li>
 * <li>{@code bonds}: the town's debt; {@code bonds buy <coins>}; {@code bonds collect}: coupons, repayments, share sales and
 *     rents waiting for you at the hall.</li>
 * <li>Operators: {@code morning} runs the capital morning now.</li>
 * </ul>
 */
public final class CapitalCommands {

    private CapitalCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("capital")
            .executes(CapitalCommands::status)
            .then(Commands.literal("books").executes(CapitalCommands::books))
            .then(Commands.literal("deposit").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                .executes(c -> money(c, (p, v) -> FolkCredit.playerDeposits(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "coins"))))))
            .then(Commands.literal("withdraw").executes(c -> money(c, (p, v) -> FolkCredit.playerWithdraws(c.getSource().getLevel(), p, v.id(), 0)))
                .then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                    .executes(c -> money(c, (p, v) -> FolkCredit.playerWithdraws(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "coins"))))))
            .then(Commands.literal("borrow").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                .executes(c -> money(c, (p, v) -> FolkCredit.playerBorrows(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "coins"))))))
            .then(Commands.literal("repay").executes(c -> money(c, (p, v) -> FolkCredit.playerRepays(c.getSource().getLevel(), p, v.id(), 0)))
                .then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                    .executes(c -> money(c, (p, v) -> FolkCredit.playerRepays(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "coins"))))))
            .then(shares())
            .then(Commands.literal("land").executes(c -> money(c, (p, v) -> String.join("\n", LandTitles.playerList(v.id())) + "\n" + LandTitles.playerLine(p, v.id())))
                .then(Commands.literal("buy").then(Commands.argument("n", IntegerArgumentType.integer(1, 1000))
                    .executes(c -> money(c, (p, v) -> LandTitles.playerBuys(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "n"))))))
                .then(Commands.literal("collect").executes(c -> money(c, (p, v) -> TownBonds.playerCollects(p, v.id())))))
            .then(Commands.literal("bonds").executes(c -> money(c, (p, v) -> TownBonds.line(v.id()) + "\n" + TownBonds.playerLine(p, v.id())))
                .then(Commands.literal("buy").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                    .executes(c -> money(c, (p, v) -> TownBonds.playerBuys(c.getSource().getLevel(), p, v.id(), IntegerArgumentType.getInteger(c, "coins"))))))
                .then(Commands.literal("collect").executes(c -> money(c, (p, v) -> TownBonds.playerCollects(p, v.id())))))
            .then(Commands.literal("morning").requires(s -> s.hasPermission(2)).executes(c -> {
                Villages.Village v = FirmCommands.near(c);
                if (v == null) return 0;
                CapitalBook.root(v.id()).remove("morning");
                CapitalBook.morning(c.getSource().getLevel(), v, c.getSource().getLevel().getDayTime() / 24000L);
                return status(c);
            }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> shares() {
        var buyN = Commands.argument("n", IntegerArgumentType.integer(1, 100))
            .executes(c -> money(c, (p, v) -> Bourse.playerBuys(c.getSource().getLevel(), p, v.id(), StringArgumentType.getString(c, "firm"),
                IntegerArgumentType.getInteger(c, "n"))));
        var sellPrice = Commands.argument("price", IntegerArgumentType.integer(1, 1000000))
            .executes(c -> money(c, (p, v) -> Bourse.playerSells(c.getSource().getLevel(), p, v.id(), StringArgumentType.getString(c, "firm"),
                IntegerArgumentType.getInteger(c, "n"), IntegerArgumentType.getInteger(c, "price"))));
        var sellN = Commands.argument("n", IntegerArgumentType.integer(1, 100))
            .executes(c -> money(c, (p, v) -> Bourse.playerSells(c.getSource().getLevel(), p, v.id(), StringArgumentType.getString(c, "firm"),
                IntegerArgumentType.getInteger(c, "n"), 0)))
            .then(sellPrice);
        return Commands.literal("shares").executes(c -> money(c, (p, v) -> Bourse.line(c.getSource().getLevel(), v.id())))
            .then(Commands.literal("buy").then(Commands.argument("firm", StringArgumentType.word()).then(buyN)))
            .then(Commands.literal("sell").then(Commands.argument("firm", StringArgumentType.word()).then(sellN)));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null) return 0;
        for (String l : CapitalReport.lines(c.getSource().getLevel(), v.id())) FirmCommands.say(c, l);
        if (c.getSource().getEntity() instanceof ServerPlayer p) {
            FirmCommands.say(c, FolkCredit.playerLine(p, v.id()));
            FirmCommands.say(c, TownBonds.playerLine(p, v.id()));
            FirmCommands.say(c, LandTitles.playerLine(p, v.id()));
        }
        return 1;
    }

    private static int books(CommandContext<CommandSourceStack> c) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null) return 0;
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
            c.getSource().sendFailure(Component.literal("The books open on a player's screen."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = Annals.snapshot(c.getSource().getLevel(), v);
        books.putString("page", "Capital");                    // the Capital page (client/CapitalPage)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /** A player's dealing at the nearest town: what it says back. */
    private static int money(CommandContext<CommandSourceStack> c, BiFunction<ServerPlayer, Villages.Village, String> f) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null) return 0;
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
            c.getSource().sendFailure(Component.literal("Only a player deals at the town's capital markets."));
            return 0;
        }
        for (String l : f.apply(p, v).split("\n")) FirmCommands.say(c, l);
        return 1;
    }
}
