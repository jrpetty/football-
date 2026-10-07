package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.Hustings;
import com.jrpetty.mcassistant.entity.PlayerLeader;
import com.jrpetty.mcassistant.entity.PlayerTrades;
import com.jrpetty.mcassistant.entity.Pledges;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [player-civic] The player's civic commands: leading a town, standing for it, and the trades learned. Registered on
 * their own (brigadier joins them to the rest of /village by name). The Leader's page, the hustings and the journal
 * run these from their buttons; each answers in chat and with the page brought up to date.
 *
 * <pre>
 *   /village leader                       the Leader's page (a player who leads), or the hustings
 *   /village leader plan food|growth|defence|trade|steady|steward
 *   /village leader build &lt;building&gt;     the next to go up, of what the town would build
 *   /village leader tithe &lt;0-20&gt;          in the hundred of what each folk holds over a dozen
 *   /village leader wages &lt;85-120&gt;        in the hundred of the standard wage
 *   /village leader envoy yes|no           the town's answer to the envoy waiting
 *   /village leader referendum &lt;question&gt; to the whole town
 *   /village civic                         the hustings: standing, promises, a speech
 *   /village civic stand | promise &lt;key&gt; | speech | withdraw
 *   /village trades                        the trades you are learning, and the next lesson
 *   /village civic now | count | judge     (ops) an election called / counted now; the promises judged now
 *   /village civic stage                   (ops, as a player) the smoke stage: the player elected, its page open
 *   /village trades stage                  (ops, as a player) the smoke stage: apprenticed to the smith, the recipe learned
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerCivicCommands {

    private PlayerCivicCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("leader").executes(ctx -> page(ctx, true))
                .then(Commands.literal("plan").then(Commands.argument("plan", StringArgumentType.word())
                    .executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.plan(l, p, StringArgumentType.getString(ctx, "plan"))))))
                .then(Commands.literal("build").then(Commands.argument("building", StringArgumentType.word())
                    .executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.build(l, p, StringArgumentType.getString(ctx, "building"))))))
                .then(Commands.literal("tithe").then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                    .executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.tithe(l, p, IntegerArgumentType.getInteger(ctx, "percent"))))))
                .then(Commands.literal("wages").then(Commands.argument("percent", IntegerArgumentType.integer(0, 200))
                    .executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.wages(l, p, IntegerArgumentType.getInteger(ctx, "percent"))))))
                .then(Commands.literal("envoy")
                    .then(Commands.literal("yes").executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.envoy(l, p, true))))
                    .then(Commands.literal("no").executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.envoy(l, p, false)))))
                .then(Commands.literal("referendum").then(Commands.argument("question", StringArgumentType.greedyString())
                    .executes(ctx -> lead(ctx, (l, p) -> PlayerLeader.referendum(l, p, StringArgumentType.getString(ctx, "question")))))))
            .then(Commands.literal("civic").executes(ctx -> page(ctx, false))
                .then(Commands.literal("stand").executes(ctx -> civic(ctx, (l, p) -> {
                    Villages.Village v = Villages.nearest(l, p.blockPosition(), Villages.VILLAGE_RANGE);
                    return v == null ? "There's no town here to stand in." : Hustings.stand(l, v.id(), p, null);
                })))
                .then(Commands.literal("promise").then(Commands.argument("key", StringArgumentType.greedyString())
                    .executes(ctx -> civic(ctx, (l, p) -> {
                        Villages.Village v = Villages.nearest(l, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
                        Pledges.Pledge want = Pledges.parse(StringArgumentType.getString(ctx, "key").trim());
                        if (v == null || want == null) return "Promise what, and where?";
                        return Hustings.promise(l, v.id(), p.getUUID(), p.getName().getString(), want);
                    }))))
                .then(Commands.literal("speech").executes(ctx -> civic(ctx, (l, p) -> Hustings.speech(l, p))))
                .then(Commands.literal("withdraw").executes(ctx -> civic(ctx, (l, p) -> {
                    Villages.Village v = Villages.nearest(l, p.blockPosition(), Villages.VILLAGE_RANGE * 2);
                    return v == null ? "There's no town here." : Hustings.withdraw(l, v.id(), p.getUUID(), p.getName().getString());
                })))
                .then(Commands.literal("close").executes(ctx -> {
                    // Shut a civic page on the screen of whoever asks (the client smoke, between its stages).
                    com.jrpetty.mcassistant.entity.PlayerCivic.send(ctx.getSource().getPlayerOrException(), "", "", List.of());
                    return 1;
                }))
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(PlayerCivicCommands::callNow))
                .then(Commands.literal("count").requires(src -> src.hasPermission(2)).executes(PlayerCivicCommands::countNow))
                .then(Commands.literal("judge").requires(src -> src.hasPermission(2)).executes(PlayerCivicCommands::judgeNow))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(PlayerCivicCommands::stageLeader)))
            .then(Commands.literal("trades").executes(PlayerCivicCommands::trades)
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(PlayerCivicCommands::stageTrades))));
    }

    /** A power the player uses: said in chat, and the page again, up to date. */
    private static int lead(CommandContext<CommandSourceStack> ctx, java.util.function.BiFunction<ServerLevel, ServerPlayer, String> power)
            throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String said = power.apply(ctx.getSource().getLevel(), p);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        PlayerLeader.openPage(p);
        return 1;
    }

    private static int civic(CommandContext<CommandSourceStack> ctx, java.util.function.BiFunction<ServerLevel, ServerPlayer, String> act)
            throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String said = act.apply(ctx.getSource().getLevel(), p);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        Hustings.openPage(p);
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> ctx, boolean leader) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        if (leader) PlayerLeader.openPage(p);
        else Hustings.openPage(p);
        return 1;
    }

    private static int trades(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerTrades.openPage(ctx.getSource().getPlayerOrException());
        return 1;
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int callNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        int n = Elections.callForTests(level, v, level.getDayTime() / 24000L + 1);
        ctx.getSource().sendSuccess(() -> Component.literal("CIVIC election called in " + Villages.name(v.id()) + ": " + n
            + " standing — " + String.join(", ", Elections.standingForTests(v.id()))), false);
        return n;
    }

    private static int countNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Elections.Result r = Elections.countForTests(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("CIVIC count in " + Villages.name(v.id()) + ": "
            + (r.winner() == null ? "nobody" : r.winner().name()) + " (" + r.voted() + " of " + r.voters() + " voted)"), false);
        return 1;
    }

    private static int judgeNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        PlayerLeader.judgeForTests(level, v, level.getDayTime() / 24000L);
        ctx.getSource().sendSuccess(() -> Component.literal("CIVIC promises in " + Villages.name(v.id()) + ": "
            + String.join(", ", PlayerLeader.promisesForTests(v.id())) + "; approval " + PlayerLeader.approval(v.id()) + "%"), false);
        return 1;
    }

    private static int stageLeader(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> out = PlayerLeader.stage(ctx.getSource().getLevel(), v, p);
        String said = String.join("\n", out);
        ctx.getSource().sendSuccess(() -> Component.literal("CIVICSTAGE\n" + said), false);
        return out.size();
    }

    private static int stageTrades(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> out = PlayerTrades.stage(ctx.getSource().getLevel(), v, p);
        String said = String.join("\n", out);
        ctx.getSource().sendSuccess(() -> Component.literal("TRADESTAGE\n" + said), false);
        return out.size();
    }
}
