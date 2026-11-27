package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.JobMarket;
import com.jrpetty.mcassistant.entity.JobSeekers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
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
 * /village jobs — the job market between towns (entity/JobMarket, entity/JobSeekers).
 *
 * <pre>
 *   /village jobs                 the nearest town's notices, its applications and their verdicts,
 *                                 who is on the road here, what is wanted elsewhere, who came and went
 *   /village jobs why &lt;name&gt;      what a folk would make of the notices: its reasons, or why it stays
 *   /village jobs books           the city books open at the job market (the Jobs page's other view)
 *   /village jobs post            the nearest town looks over its notices now (ops)
 *   /village jobs decide          its leader decides the applications now (ops)
 *   /village jobs look [name]     a folk (the one with most reason to, if none is named) goes to read the
 *                                 board now, and applies if a notice is for it and it has a reason (ops)
 *   /village jobs want &lt;trade&gt;    the nearest town puts a notice up for that trade now, whatever it is
 *                                 short of, at the trade's real wage (ops; for setting a scene)
 *   /village jobs pact            the nearest town and its nearest neighbour agree to trade, so word
 *                                 of each other's notices passes (ops; for setting a scene)
 * </pre>
 */
public final class JobMarketCommands {

    private JobMarketCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("jobs").executes(JobMarketCommands::status)
            .then(Commands.literal("why")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> why(ctx, StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("books").executes(JobMarketCommands::books))
            .then(Commands.literal("post").requires(src -> src.hasPermission(2)).executes(JobMarketCommands::post))
            .then(Commands.literal("decide").requires(src -> src.hasPermission(2)).executes(JobMarketCommands::decide))
            .then(Commands.literal("pact").requires(src -> src.hasPermission(2)).executes(JobMarketCommands::pact))
            .then(Commands.literal("want").requires(src -> src.hasPermission(2))
                .then(Commands.argument("trade", StringArgumentType.word())
                    .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(tradeWords(), b))
                    .executes(ctx -> want(ctx, StringArgumentType.getString(ctx, "trade")))))
            .then(Commands.literal("look").requires(src -> src.hasPermission(2))
                .executes(ctx -> look(ctx, ""))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> look(ctx, StringArgumentType.getString(ctx, "name")))));
    }

    @Nullable
    private static Villages.Village nearest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village yet."));
        return v;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String line) {
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        for (String l : JobMarket.status(ctx.getSource().getLevel(), v.id())) say(ctx, l);
        return 1;
    }

    private static int post(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        List<JobMarket.Opening> up = JobMarket.post(ctx.getSource().getLevel(), v);
        say(ctx, "JOBS-POST " + Villages.name(v.id()) + ": " + up.size() + " new " + (up.size() == 1 ? "notice" : "notices")
            + ", " + JobMarket.open(v.id()).size() + " up in all");
        return 1;
    }

    private static int decide(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        int n = JobMarket.considerNow(ctx.getSource().getLevel(), v);
        say(ctx, "JOBS-DECIDE " + Villages.name(v.id()) + ": " + n + " taken on");
        return 1;
    }

    private static int pact(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        Villages.Village other = null;
        double best = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double d = o.centre().distSqr(v.centre());
            if (d < best) { best = d; other = o; }
        }
        if (other == null) {
            ctx.getSource().sendFailure(Component.literal("No other village to agree with."));
            return 0;
        }
        com.jrpetty.mcassistant.village.Ledger.note(v.id(), "pact/" + other.id(), "trade");
        com.jrpetty.mcassistant.village.Ledger.note(other.id(), "pact/" + v.id(), "trade");
        if (!com.jrpetty.mcassistant.village.Ledger.knowEachOther(v.id(), other.id())) {
            com.jrpetty.mcassistant.village.Ledger.relate(v.id(), other.id(), 25);
        }
        long day = ctx.getSource().getLevel().getDayTime() / 24000L;
        String line = Villages.name(v.id()) + " and " + Villages.name(other.id()) + " agreed to trade";
        Villages.tell(v.id(), day, line);
        Villages.tell(other.id(), day, line);
        say(ctx, "JOBS-PACT " + Villages.name(v.id()) + " and " + Villages.name(other.id()) + " (" + (int) Math.sqrt(best) + " blocks apart)");
        return 1;
    }

    @Nullable
    private static VillageFolkEntity named(CommandContext<CommandSourceStack> ctx, String name) {
        String n = name.trim();
        Villages.Village v = nearest(ctx);
        if (v != null) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && f.displayNameCap().equalsIgnoreCase(n)) return f;
            }
        }
        for (Villages.Village o : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(o.id())) {
                if (a instanceof VillageFolkEntity f && f.displayNameCap().equalsIgnoreCase(n)) return f;
            }
        }
        ctx.getSource().sendFailure(Component.literal("No folk called " + n + " is about."));
        return null;
    }

    private static int why(CommandContext<CommandSourceStack> ctx, String name) {
        VillageFolkEntity f = named(ctx, name);
        if (f == null) return 0;
        say(ctx, "JOBS-WHY " + f.displayNameCap() + " of " + (f.ownerId() == null ? "nowhere" : Villages.name(f.ownerId())) + ": "
            + JobSeekers.reasonsLine(f));
        String card = JobMarket.cardLine(f);
        if (!card.isEmpty()) say(ctx, "  Its card: " + card);
        return 1;
    }

    private static int look(CommandContext<CommandSourceStack> ctx, String name) {
        VillageFolkEntity f = name.isBlank() ? likeliest(ctx) : named(ctx, name);
        if (f == null) return 0;
        JobSeekers.sendToBoard(f);
        BlockPos at = f.blockPosition();
        say(ctx, "JOBS-LOOK " + f.displayNameCap() + " at " + at.getX() + " " + at.getY() + " " + at.getZ() + " goes to read the notices: "
            + JobSeekers.reasonsLine(f));
        return 1;
    }

    /** The grown folk of the nearest town most likely to apply somewhere: one with a notice for it, else any not the elder. */
    @Nullable
    private static VillageFolkEntity likeliest(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return null;
        VillageFolkEntity any = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || f.getUUID().equals(Villages.elder(v.id()))) continue;
            if (JobSeekers.reasonsLine(f).startsWith("would apply")) return f;
            if (any == null) any = f;
        }
        if (any == null) ctx.getSource().sendFailure(Component.literal("Nobody here to send."));
        return any;
    }

    private static List<String> tradeWords() {
        List<String> out = new java.util.ArrayList<>();
        for (AssistantEntity.StationTask t : AssistantEntity.StationTask.values()) {
            if (t != AssistantEntity.StationTask.NONE) out.add(t.name().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private static int want(CommandContext<CommandSourceStack> ctx, String trade) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        AssistantEntity.StationTask t = JobMarket.named(trade.toUpperCase(java.util.Locale.ROOT));
        if (t == null || t == AssistantEntity.StationTask.NONE) {
            for (AssistantEntity.StationTask s : AssistantEntity.StationTask.values()) {
                if (s != AssistantEntity.StationTask.NONE && JobMarket.noun(s).equalsIgnoreCase(trade)) t = s;
            }
        }
        if (t == null || t == AssistantEntity.StationTask.NONE) {
            ctx.getSource().sendFailure(Component.literal("No trade called " + trade + "."));
            return 0;
        }
        JobMarket.Opening o = JobMarket.postFor(ctx.getSource().getLevel(), v, t);
        say(ctx, "JOBS-WANT " + Villages.name(v.id()) + ": wanted, " + JobMarket.a(o.title()) + ", " + o.wage() + " a day; " + o.wants());
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
        books.putInt("tab", 5);                              // the Jobs page (client/CityScreen.TABS)
        books.putBoolean("jobmarket_view", true);            // at its job market
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }
}
