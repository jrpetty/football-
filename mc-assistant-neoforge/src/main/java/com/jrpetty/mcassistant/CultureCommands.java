package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Playbill;
import com.jrpetty.mcassistant.entity.Theatre;
import com.jrpetty.mcassistant.entity.TownWonders;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * /village culture [batchD] — the town's culture (entity/Culture): its banner and motto, its customs, the
 * theatre and its plays, the band and the choir, its pictures and its plaques.
 *
 * <pre>
 *   /village culture          the nearest village's: the banner in words and where it hangs, the motto, each
 *                             custom and its next day, the theatre's plays, the band, the choir, the pictures
 *                             and where they hang, the plaques and where they stand
 *   /village culture now      (ops) the banner, the motto, the shop's sign, the pictures and the plaques: every
 *                             piece the stores run to put up now
 *   /village culture play     (ops) tonight's play begun now in the nearest village with a theatre, whatever the
 *                             day: its players cast and its title
 *   /village culture stage    (ops) a theatre set out where you stand for the pictures: the town's banners on its
 *                             back wall, players on the stage, an audience on the benches; says where to look from
 *
 *   [culture-wonders]
 *   /village theatre           the nearest town's theatre: the playbill, the company, the wardrobe, the reviews
 *   /village theatre ticket    a seat at the coming play for a coin, and its programme
 *   /village theatre ask       ask the players for a play of your own deeds
 *   /village theatre play      (ops) a play begun now; "rehearse" a rehearsal; "stage [battle]" the pictures' theatre
 *   /village wonder            the town's own wonder: its stage, how far on, what it wants, its fund and givers
 *   /village wonder give N     N coins to the wonder fund
 *   /village wonder start [minster|belfry|amphitheatre]   (ops) put to the council now, carried by decree
 *   /village wonder stage [paid]   (ops) the stage under way laid now (for nothing, or out of the stores)
 *   /village wonder finish     (ops) every stage laid now, opened this evening
 *   /village wonder picture KIND PERCENT   (ops) a wonder part built where you stand, for the pictures
 * </pre>
 */
public final class CultureCommands {

    private CultureCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("culture")
            .executes(CultureCommands::status)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(CultureCommands::now))
            .then(Commands.literal("play").requires(src -> src.hasPermission(2)).executes(CultureCommands::play))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(CultureCommands::stage));
    }

    /** [culture-wonders] /village theatre. */
    public static LiteralArgumentBuilder<CommandSourceStack> theatre() {
        return Commands.literal("theatre")
            .executes(ctx -> say(ctx, v -> String.join("\n", Theatre.status(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("ticket").executes(ctx -> say(ctx, v -> Playbill.buy(ctx.getSource().getLevel(), v, ctx.getSource().getPlayerOrException()))))
            .then(Commands.literal("ask").executes(ctx -> say(ctx, v -> Playbill.request(ctx.getSource().getLevel(), v, ctx.getSource().getPlayerOrException()))))
            .then(Commands.literal("play").requires(src -> src.hasPermission(2)).executes(CultureCommands::play))
            .then(Commands.literal("rehearse").requires(src -> src.hasPermission(2)).executes(ctx -> say(ctx, v -> {
                String t = Theatre.rehearseForTests(ctx.getSource().getLevel(), v);
                return t == null ? "No rehearsal: no house, or not two players free." : "REHEARSAL of “" + t + "”";
            })))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(CultureCommands::stage)
                .then(Commands.argument("what", StringArgumentType.word()).executes(ctx -> {
                    Villages.Village v = near(ctx, 200);
                    if (v == null) return 0;
                    List<String> views = Theatre.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()),
                        StringArgumentType.getString(ctx, "what"));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", views)), false);
                    return views.size();
                })));
    }

    /** [culture-wonders] /village wonder. */
    public static LiteralArgumentBuilder<CommandSourceStack> wonder() {
        return Commands.literal("wonder")
            .executes(ctx -> say(ctx, v -> String.join("\n", TownWonders.status(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("give").then(Commands.argument("coins", IntegerArgumentType.integer(1, 10000)).executes(ctx -> say(ctx,
                v -> TownWonders.donate(ctx.getSource().getLevel(), v, ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "coins"))))))
            .then(Commands.literal("start").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> TownWonders.startNow(ctx.getSource().getLevel(), v, null)))
                .then(Commands.argument("kind", StringArgumentType.word()).executes(ctx -> say(ctx,
                    v -> TownWonders.startNow(ctx.getSource().getLevel(), v, StringArgumentType.getString(ctx, "kind"))))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> TownWonders.stageNow(ctx.getSource().getLevel(), v, true)))
                .then(Commands.literal("paid").executes(ctx -> say(ctx, v -> TownWonders.stageNow(ctx.getSource().getLevel(), v, false)))))
            .then(Commands.literal("finish").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> TownWonders.finishNow(ctx.getSource().getLevel(), v, true))))
            .then(Commands.literal("picture").requires(src -> src.hasPermission(2))
                .then(Commands.argument("kind", StringArgumentType.word())
                    .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                        Villages.Village v = near(ctx, 200);
                        if (v == null) return 0;
                        List<String> views = TownWonders.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()),
                            StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "percent"));
                        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", views)), false);
                        return views.size();
                    }))));
    }

    /** What a command says about the nearest town (a failure if there is none near). */
    private interface Said {
        String of(Villages.Village v) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, Said what) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String out = what.of(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx, int range) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, range);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        List<String> lines = Culture.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        String did = Heraldry.putForTests(level, v);
        int plaques = com.jrpetty.mcassistant.entity.Plaques.putForTests(level, v).size();
        ctx.getSource().sendSuccess(() -> Component.literal("CULTURE " + Villages.name(v.id()) + ": " + (did.isEmpty() ? "nothing more to put up" : did)
            + "; plaques " + plaques), false);
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String title = Theatre.beginForTests(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(title == null
            ? "No play: the town wants two players (folk who love reading, music or whittling) free to put one on."
            : "PLAY “" + title + "” with " + String.join(", ", Theatre.castForTests(v.id()))), false);
        return title == null ? 0 : 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = near(ctx, 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("Stand within two hundred blocks of a village: the theatre is its."));
            return 0;
        }
        List<String> views = Theatre.stage(level, v, at);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", views)), false);
        return views.size();
    }
}
