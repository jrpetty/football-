package com.jrpetty.mcassistant.entity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Map;

/**
 * [econ-1a] The market's commands, under /village:
 * <ul>
 * <li>{@code /village market}: the storehouse's bid and ask for the goods most traded, its stock and today's trades, its
 *     week, what it owes and is owed, the privatisation programme; {@code page} opens the books at the Market page;
 *     {@code open} (operators) holds Market Opening Day now.</li>
 * <li>{@code /village accounts stragglers}: today's takes nobody's account answered for, by where they came from;
 *     {@code /village accounts <folk name|business id>}: one account's standing; {@code ious}: the town's IOUs.</li>
 * <li>{@code /village money audit}: the town's coin counted to the penny, holder by holder, and what is owed apart.</li>
 * </ul>
 */
public final class MarketCommands {

    private MarketCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> market() {
        return Commands.literal("market")
            .executes(c -> say(c, v -> MarketMaker.words(c.getSource().getLevel(), v.id())))
            .then(Commands.literal("page").executes(MarketCommands::page))
            .then(Commands.literal("open").requires(s -> s.hasPermission(2)).executes(c -> say(c, v -> {
                if (MarketMaker.openedDay(v.id()) >= 0) return "The market of " + Villages.name(v.id()) + " is open already (day " + (MarketMaker.openedDay(v.id()) + 1) + ").";
                if (!MarketMaker.enabled()) return "The market is not at work here (a plain test town).";
                MarketMaker.openForTests(c.getSource().getLevel(), v);
                return "Market Opening Day in " + Villages.name(v.id()) + ".";
            })));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> accounts() {
        return Commands.literal("accounts")
            .then(Commands.literal("stragglers").executes(c -> say(c, v -> {
                ServerLevel level = c.getSource().getLevel();
                Map<String, Long> sites = Accounts.stragglerSites(level, v.id());
                StringBuilder sb = new StringBuilder("Takes nobody's account answered for today in " + Villages.name(v.id()) + ": "
                    + Accounts.stragglersToday(level, v.id()) + " (all told " + Accounts.stragglersAll(v.id()) + ")");
                for (Map.Entry<String, Long> e : sites.entrySet()) sb.append("\n  ").append(e.getValue()).append(" from ").append(e.getKey());
                Map<String, Long> sys = Accounts.systemSites(level, v.id());
                if (!sys.isEmpty()) sb.append("\nThe town's own systems, on its works account:");
                for (Map.Entry<String, Long> e : sys.entrySet()) sb.append("\n  ").append(e.getValue()).append(" from ").append(e.getKey());
                return sb.toString();
            })))
            .then(Commands.literal("ious").executes(c -> say(c, v -> "The town's IOUs to its market: " + Businesses.pence(Accounts.debts(v.id()))
                + "\n  " + String.join("\n  ", Accounts.debtLines(v.id())))))
            .then(Commands.argument("who", StringArgumentType.greedyString()).executes(c -> say(c, v -> {
                String who = StringArgumentType.getString(c, "who").trim();
                ServerLevel level = c.getSource().getLevel();
                Business b = Businesses.of(v.id(), who);
                if (b != null) return Accounts.words(level, v.id(), Accounts.Account.firm(b));
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity f && f.displayNameCap().toLowerCase(Locale.ROOT).startsWith(who.toLowerCase(Locale.ROOT))) {
                        return Accounts.words(level, v.id(), Accounts.Account.folk(f));
                    }
                }
                for (Accounts.Dept d : Accounts.Dept.values()) {
                    if (d.name().equalsIgnoreCase(who)) return Accounts.words(level, v.id(), Accounts.Account.dept(d));
                }
                return "No folk, business or department called " + who + " in " + Villages.name(v.id()) + ".";
            })));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> money() {
        return Commands.literal("money")
            .then(Commands.literal("audit").executes(c -> say(c, v -> CoinCensus.page(v.id()))));
    }

    interface Words {
        String of(Villages.Village v);
    }

    private static int say(CommandContext<CommandSourceStack> c, Words w) {
        Villages.Village v = BusinessBooks.near(c);
        if (v == null) return 0;
        String text = w.of(v);
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> c) {
        Villages.Village v = BusinessBooks.near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        CompoundTag books = Annals.snapshot(c.getSource().getLevel(), v);
        books.putString("page", "Market");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }
}
