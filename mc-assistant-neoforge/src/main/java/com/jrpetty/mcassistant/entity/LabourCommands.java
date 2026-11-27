package com.jrpetty.mcassistant.entity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * [labour] /village labour: the town's labour market (LabourMarket).
 * <ul>
 * <li>{@code /village labour}: who is in work, their own masters and out of work; the openings with their wages and
 *     applicants; the wage index; the latest hirings, quits and lay-offs.</li>
 * <li>{@code /village labour books}: the city books open at the Jobs page's labour market.</li>
 * <li>{@code /village labour post <firm> [wage]}: a player's firm posts an opening (at so many coins a day, or its going
 *     offer); {@code hire <firm>} takes on the best who applied.</li>
 * <li>{@code /village labour minwage <coins>}: the town's leader (a player who leads, or an operator) sets the minimum wage
 *     (0 for none: the living wage is the least).</li>
 * <li>Operators: {@code morning} runs the morning's labour market now; {@code round} the day's round for those out of work.</li>
 * </ul>
 * A player takes a job of its own at the market's wages through the shifts (the Work tab): their pay follows the town's
 * wages.
 */
public final class LabourCommands {

    private LabourCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("labour")
            .executes(LabourCommands::status)
            .then(Commands.literal("books").executes(LabourCommands::books))
            .then(Commands.literal("post").then(Commands.argument("id", StringArgumentType.word()).executes(c -> post(c, 0))
                .then(Commands.argument("wage", IntegerArgumentType.integer(1, 200)).executes(c -> post(c, IntegerArgumentType.getInteger(c, "wage"))))))
            .then(Commands.literal("hire").then(Commands.argument("id", StringArgumentType.word()).executes(LabourCommands::hire)))
            .then(Commands.literal("minwage").then(Commands.argument("coins", IntegerArgumentType.integer(0, 200)).executes(LabourCommands::minwage)))
            .then(Commands.literal("morning").requires(s -> s.hasPermission(2)).executes(c -> {
                Villages.Village v = FirmCommands.near(c);
                if (v == null) return 0;
                LabourMarket.morningNow(c.getSource().getLevel(), v);
                return status(c);
            }))
            .then(Commands.literal("round").requires(s -> s.hasPermission(2)).executes(c -> {
                Villages.Village v = FirmCommands.near(c);
                if (v == null) return 0;
                int n = LabourMarket.roundForTests(c.getSource().getLevel(), v);
                return FirmCommands.say(c, n + " out of work taken on.");
            }));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null) return 0;
        for (String l : LabourMarket.status(c.getSource().getLevel(), v.id())) FirmCommands.say(c, l);
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
        books.putString("page", "Jobs");                       // the Jobs page (client/CityScreen)
        books.putBoolean("labour_view", true);                 // at its labour market
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int post(CommandContext<CommandSourceStack> c, int wage) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Business b = FirmCommands.owned(c, v, p);
        if (b == null) return 0;
        return FirmCommands.say(c, LabourMarket.post(c.getSource().getLevel(), v.id(), b, wage));
    }

    private static int hire(CommandContext<CommandSourceStack> c) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Business b = FirmCommands.owned(c, v, p);
        if (b == null) return 0;
        return FirmCommands.say(c, LabourMarket.hireBest(c.getSource().getLevel(), v.id(), b));
    }

    private static int minwage(CommandContext<CommandSourceStack> c) {
        Villages.Village v = FirmCommands.near(c);
        if (v == null) return 0;
        UUID leader = PlayerLeader.leaderId(v.id());
        boolean may = c.getSource().hasPermission(2)
            || c.getSource().getEntity() instanceof ServerPlayer p && leader != null && leader.equals(p.getUUID());
        if (!may) return FirmCommands.say(c, "Only the town's leader sets its minimum wage.");
        String by = c.getSource().getEntity() instanceof ServerPlayer p ? p.getName().getString() : "the leader";
        return FirmCommands.say(c, LabourMarket.setMinimum(v.id(), IntegerArgumentType.getInteger(c, "coins"), by));
    }
}
