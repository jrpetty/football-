package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * [econ-1b] /village firms: the businesses the town's own folk opened (Enterprise), the town's wants as the entrepreneurs
 * see them, and a player's own charter.
 * <ul>
 * <li>{@code /village firms}: every firm, its owner, trade, week's profit, hands, share of its market and how it stands.</li>
 * <li>{@code /village firms wants}: the morning's look at the town's wants, kind by kind.</li>
 * <li>{@code /village firms charter <kind>}: a player opens a firm of its own at the hall, for a charter fee of ten coins to
 *     the treasury; then {@code hire <id>} takes on a hand, {@code markup <id> <percent>} sets its prices, and the business
 *     commands (/village business order, invest, draw, books) do the rest.</li>
 * <li>Operators: {@code found-now <kind> [folk]} opens one now; {@code bankrupt-now <id>} puts one into bankruptcy;
 *     {@code morning} runs the firms' morning.</li>
 * </ul>
 */
public final class FirmCommands {

    private FirmCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("firms")
            .executes(FirmCommands::list)
            .then(Commands.literal("wants").executes(FirmCommands::wants))
            .then(Commands.literal("charter").then(Commands.argument("kind", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(BusinessTypes.keys(), b)).executes(FirmCommands::charter)))
            .then(Commands.literal("hire").then(Commands.argument("id", StringArgumentType.word()).executes(FirmCommands::hire)))
            .then(Commands.literal("markup").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("percent", IntegerArgumentType.integer(5, 200)).executes(FirmCommands::markup))))
            .then(Commands.literal("found-now").requires(s -> s.hasPermission(2)).then(Commands.argument("kind", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(BusinessTypes.keys(), b)).executes(c -> foundNow(c, null))
                .then(Commands.argument("folk", StringArgumentType.greedyString()).executes(c -> foundNow(c, StringArgumentType.getString(c, "folk"))))))
            .then(Commands.literal("bankrupt-now").requires(s -> s.hasPermission(2)).then(Commands.argument("id", StringArgumentType.word()).executes(FirmCommands::bankruptNow)))
            .then(Commands.literal("morning").requires(s -> s.hasPermission(2)).executes(c -> {
                Villages.Village v = near(c);
                if (v == null) return 0;
                Enterprise.morningForTests(c.getSource().getLevel(), v, false);
                c.getSource().sendSuccess(() -> Component.literal(Enterprise.words(c.getSource().getLevel(), v.id())), false);
                return 1;
            }));
    }

    @Nullable
    static Villages.Village near(CommandContext<CommandSourceStack> c) {
        Villages.Village v = Villages.nearest(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()), 400);
        if (v == null) c.getSource().sendFailure(Component.literal("No village near."));
        return v;
    }

    static int say(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        return say(c, Enterprise.words(c.getSource().getLevel(), v.id()));
    }

    private static int wants(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        StringBuilder sb = new StringBuilder("What " + Villages.name(v.id()) + " wants, as a folk with a mind to open a business sees it (a week):\n");
        for (Enterprise.Opportunity o : Enterprise.scan(c.getSource().getLevel(), v)) sb.append("  ").append(o.words()).append("\n");
        return say(c, sb.toString());
    }

    private static int charter(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        return say(c, Enterprise.charter(c.getSource().getLevel(), v, p, StringArgumentType.getString(c, "kind")));
    }

    @Nullable
    static Business owned(CommandContext<CommandSourceStack> c, Villages.Village v, ServerPlayer p) {
        Business b = Businesses.of(v.id(), StringArgumentType.getString(c, "id"));
        if (b == null || !Enterprise.isFirm(b) || !b.open) {
            say(c, "No firm by that name here: /village firms lists them.");
            return null;
        }
        if (!b.ownedBy(p.getUUID()) && !p.hasPermissions(2)) {
            say(c, "That isn't yours to run.");
            return null;
        }
        return b;
    }

    private static int hire(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Business b = owned(c, v, p);
        if (b == null) return 0;
        VillageFolkEntity f = Enterprise.hireForTests(c.getSource().getLevel(), v, b);
        return say(c, f == null ? "Nobody the town can spare will take the work just now." : f.displayNameCap() + " is taken on at " + b.name
            + ", paid " + Wealth.wage(f) + " a day out of its till.");
    }

    private static int markup(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Business b = owned(c, v, p);
        if (b == null) return 0;
        double was = b.markup;
        b.markup = IntegerArgumentType.getInteger(c, "percent") / 100.0;
        b.note(Businesses.today(c.getSource().getLevel()), "markup " + Businesses.pct(was) + " -> " + Businesses.pct(b.markup) + ", set by its owner");
        Businesses.dirty();
        return say(c, b.name + " now asks its costs and " + Businesses.pct(b.markup) + " (never over three fifths above the going price).");
    }

    private static int foundNow(CommandContext<CommandSourceStack> c, @Nullable String folk) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        BusinessTypes.BusinessType t = BusinessTypes.of(StringArgumentType.getString(c, "kind"));
        if (t == null) return say(c, "No such kind of business. The kinds: " + String.join(", ", BusinessTypes.keys()) + ".");
        ServerLevel level = c.getSource().getLevel();
        VillageFolkEntity f = null;
        if (folk != null) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity x && x.displayNameCap().toLowerCase(Locale.ROOT).startsWith(folk.toLowerCase(Locale.ROOT))) { f = x; break; }
            }
            if (f == null) return say(c, "Nobody called " + folk + " here.");
        } else {
            f = Enterprise.founderFor(v.id(), t, 0);
            if (f == null) {
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity x && !x.isBaby() && !Enterprise.ownsAny(v.id(), x.getUUID()) && Orders.elderOf(v.id()) != x) { f = x; break; }
                }
            }
        }
        if (f == null) return say(c, "Nobody to open it.");
        Business b = Enterprise.found(level, v, t, f, null, Businesses.today(level), null);
        if (b == null) return say(c, f.displayNameCap() + " could not open a " + t.noun() + " (no ground for its work).");
        net.minecraft.nbt.CompoundTag r = Enterprise.recordForTests(v.id(), b);
        String sign = r != null && r.contains("sign") ? " SIGN " + BlockPos.of(r.getLong("sign")).getX() + " " + BlockPos.of(r.getLong("sign")).getY() + " "
            + BlockPos.of(r.getLong("sign")).getZ() : "";
        return say(c, "OPENED " + b.id + sign + ": " + Enterprise.line(level, v.id(), b));
    }

    private static int bankruptNow(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        Business b = Businesses.of(v.id(), StringArgumentType.getString(c, "id"));
        if (b == null || !Enterprise.isFirm(b) || !b.open) return say(c, "No open firm by that name here.");
        ServerLevel level = c.getSource().getLevel();
        Enterprise.bankrupt(level, v.id(), b, Businesses.today(level), "put into bankruptcy by order");
        return say(c, "BANKRUPT " + b.id + ": " + Enterprise.line(level, v.id(), b));
    }
}
