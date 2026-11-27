package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Interviews;
import com.jrpetty.mcassistant.entity.Villages;
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
import java.util.List;

/**
 * [interviews] /village interviews — the town's job interviews (entity/Interviews).
 *
 * <pre>
 *   /village interviews                  the nearest town's interviews: set, on, and held, each candidate's score
 *   /village interviews page             the interview page: the candidates' particulars (and, for the player who
 *                                        leads or a guest on the panel, a Choose for each)
 *   /village interviews choose &lt;name&gt;    the player who leads chooses (its choice stands), or a guest on the panel
 *                                        votes; "panel" leaves it to the panel
 *   /village interviews recommend &lt;name&gt; a good word put in for a candidate, as the talk screen takes it
 *   /village interviews panel            an honoured guest of the town asks to sit on the panel
 *   /village interviews books            the city books open at the Interviews page
 *   /village interviews stage &lt;post&gt;     (ops) an interview set now for a post, with the town's best: teacher,
 *                                        librarian, constable, caveleader, caveplace, ferryman, auctioneer, banker,
 *                                        steward, fletcher, golemkeeper, cartographer, master_&lt;trade&gt;, or a
 *                                        trade's word (a notice put up for it)
 *   /village interviews now              (ops) the town's next interview begun at once
 *   /village interviews hurry on|off     (ops) lines every half-second, for a quick look
 * </pre>
 */
public final class InterviewCommands {

    private InterviewCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("interviews").executes(InterviewCommands::status)
            .then(Commands.literal("page").executes(ctx -> {
                Interviews.page(ctx.getSource().getPlayerOrException());
                return 1;
            }))
            .then(Commands.literal("books").executes(InterviewCommands::books))
            .then(Commands.literal("choose")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        String said = Interviews.choose(ctx.getSource().getLevel(), p, StringArgumentType.getString(ctx, "name"));
                        say(ctx, said);
                        Interviews.page(p);
                        return 1;
                    })))
            .then(Commands.literal("recommend")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        Villages.Village v = nearest(ctx);
                        if (v == null) return 0;
                        String name = StringArgumentType.getString(ctx, "name");
                        say(ctx, Interviews.recommendWord(ctx.getSource().getLevel(), p, v.id(), "I'd recommend " + name + " for the post"));
                        return 1;
                    })))
            .then(Commands.literal("panel").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                Villages.Village v = nearest(ctx);
                if (v == null) return 0;
                say(ctx, Interviews.panelSeat(ctx.getSource().getLevel(), p, v.id()));
                return 1;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .then(Commands.argument("post", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Interviews.postKeys(), b))
                    .executes(ctx -> {
                        Villages.Village v = nearest(ctx);
                        if (v == null) return 0;
                        List<String> out = Interviews.stage(ctx.getSource().getLevel(), v, StringArgumentType.getString(ctx, "post"));
                        say(ctx, String.join(" | ", out));
                        return out.size();
                    })))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = nearest(ctx);
                if (v == null) return 0;
                say(ctx, Interviews.now(ctx.getSource().getLevel(), v));
                return 1;
            }))
            .then(Commands.literal("hurry").requires(src -> src.hasPermission(2))
                .then(Commands.literal("on").executes(ctx -> { Interviews.hurryForTests(true); say(ctx, "Interviews: a line every half-second."); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { Interviews.hurryForTests(false); say(ctx, "Interviews: at their own pace."); return 1; })));
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

    /** The town's books, open at the Interviews page (client/CityScreen.TABS). */
    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("The books open on a player's screen."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Interviews");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = nearest(ctx);
        if (v == null) return 0;
        for (String l : Interviews.status(ctx.getSource().getLevel(), v.id())) say(ctx, l);
        return 1;
    }
}
