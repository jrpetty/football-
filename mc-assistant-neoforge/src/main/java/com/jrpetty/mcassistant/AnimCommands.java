package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AnimStage;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * [anim] /village anim — the folk's animations, for a look at them (screenshots, testing). Ops and creative players.
 *
 * <pre>
 *   /village anim                     what the nearest folk is doing just now (its act, as the server names it)
 *   /village anim list                every act there is
 *   /village anim play ACT [TICKS]    the nearest folk does it (for TICKS, ten seconds if not said; a loop goes on)
 *   /village anim all ACT [TICKS]     every folk within 16 blocks does it
 *   /village anim mark MARK           the picture over the nearest folk's head (idea, coin, question, heart...)
 *   /village anim stop                the nearest folk back to what it was doing
 *   /village anim befriend            (a player) the nearest folk thinks well of you (to see your emotes answered)
 *   /village anim friend [front]      (a player) a folk stood up beside you that thinks well of you (front: face to face)
 *   /village anim stage               the smoke's stage where you stand: a folk doing each act, in rows (VIEW lines)
 *   /village emote EMOTE [ANGLE]      (anybody) the player's own emote, as the wheel sends it, its turned camera at ANGLE
 *                                    degrees round from its front (90: side on) if asked; "wheel" opens the wheel
 * </pre>
 */
public final class AnimCommands {

    private AnimCommands() {}

    private static final SuggestionProvider<CommandSourceStack> ACTS = (ctx, b) ->
        SharedSuggestionProvider.suggest(Arrays.stream(Act.values()).map(Act::word), b);
    private static final SuggestionProvider<CommandSourceStack> MARKS = (ctx, b) ->
        SharedSuggestionProvider.suggest(Arrays.stream(FolkAction.Mark.values()).map(m -> m.name().toLowerCase(java.util.Locale.ROOT)), b);

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("anim").requires(AnimCommands::allowed).executes(AnimCommands::now)
                .then(Commands.literal("list").executes(AnimCommands::list))
                .then(Commands.literal("play").then(Commands.argument("act", StringArgumentType.word()).suggests(ACTS)
                    .executes(ctx -> play(ctx, 200, false))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 72000))
                        .executes(ctx -> play(ctx, IntegerArgumentType.getInteger(ctx, "ticks"), false)))))
                .then(Commands.literal("all").then(Commands.argument("act", StringArgumentType.word()).suggests(ACTS)
                    .executes(ctx -> play(ctx, 200, true))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 72000))
                        .executes(ctx -> play(ctx, IntegerArgumentType.getInteger(ctx, "ticks"), true)))))
                .then(Commands.literal("mark").then(Commands.argument("mark", StringArgumentType.word()).suggests(MARKS)
                    .executes(AnimCommands::mark)))
                .then(Commands.literal("stop").executes(AnimCommands::stop))
                .then(Commands.literal("befriend").executes(AnimCommands::befriend))
                .then(Commands.literal("friend").executes(ctx -> friend(ctx, false))
                    .then(Commands.literal("front").executes(ctx -> friend(ctx, true))))
                .then(Commands.literal("stage").executes(AnimCommands::stage))));
        // The player's own emotes, typed (the wheel and the talk screen send the same): anybody, at the same pace.
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("emote").then(Commands.argument("emote", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(com.jrpetty.mcassistant.entity.EmoteRules.EMOTES).map(Act::word), b))
                .executes(ctx -> emote(ctx, com.jrpetty.mcassistant.net.PlayerEmotePayload.ANY_ANGLE))
                .then(Commands.argument("angle", IntegerArgumentType.integer(-180, 180))
                    .executes(ctx -> emote(ctx, IntegerArgumentType.getInteger(ctx, "angle")))))));
    }

    /** An op, or a player in creative. */
    static boolean allowed(CommandSourceStack src) {
        return src.hasPermission(2) || src.getEntity() instanceof net.minecraft.world.entity.player.Player p && p.isCreative();
    }

    @Nullable
    private static VillageFolkEntity nearest(CommandContext<CommandSourceStack> ctx) {
        Vec3 at = ctx.getSource().getPosition();
        VillageFolkEntity best = null;
        double d = 16.0 * 16.0;
        for (VillageFolkEntity f : ctx.getSource().getLevel().getEntitiesOfClass(VillageFolkEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(16.0))) {
            double e = f.distanceToSqr(at);
            if (e < d) { d = e; best = f; }
        }
        if (best == null) ctx.getSource().sendFailure(Component.literal("No folk within sixteen blocks."));
        return best;
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        VillageFolkEntity f = nearest(ctx);
        if (f == null) return 0;
        String line = f.displayNameCap() + ": " + FolkAction.now(f).word() + " (sent " + FolkAction.sendsForTests(f) + " times)";
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> work = new ArrayList<>(), life = new ArrayList<>();
        for (Act a : Act.values()) if (a != Act.NONE) (a.work() ? work : life).add(a.word());
        ctx.getSource().sendSuccess(() -> Component.literal("Work: " + String.join(", ", work) + "\nLife: " + String.join(", ", life)), false);
        return work.size() + life.size();
    }

    private static int play(CommandContext<CommandSourceStack> ctx, int ticks, boolean all) {
        Act a = Act.named(StringArgumentType.getString(ctx, "act"));
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No such act. /village anim list"));
            return 0;
        }
        List<VillageFolkEntity> who = new ArrayList<>();
        if (all) {
            Vec3 at = ctx.getSource().getPosition();
            who.addAll(ctx.getSource().getLevel().getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(16.0)));
        } else {
            VillageFolkEntity f = nearest(ctx);
            if (f != null) who.add(f);
        }
        for (VillageFolkEntity f : who) FolkAction.play(f, a, ticks);
        int n = who.size();
        ctx.getSource().sendSuccess(() -> Component.literal(n + " folk: " + a.word() + " for " + ticks + " ticks"), false);
        return n;
    }

    private static int mark(CommandContext<CommandSourceStack> ctx) {
        FolkAction.Mark m = FolkAction.Mark.named(StringArgumentType.getString(ctx, "mark"));
        VillageFolkEntity f = m == null ? null : nearest(ctx);
        if (f == null) {
            if (m == null) ctx.getSource().sendFailure(Component.literal("No such mark."));
            return 0;
        }
        FolkAction.mark(f, m);
        ctx.getSource().sendSuccess(() -> Component.literal(f.displayNameCap() + ": " + m.name().toLowerCase(java.util.Locale.ROOT)), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        VillageFolkEntity f = nearest(ctx);
        if (f == null) return 0;
        FolkAction.stop(f);
        ctx.getSource().sendSuccess(() -> Component.literal(f.displayNameCap() + " is back to " + FolkAction.now(f).word()), false);
        return 1;
    }

    private static int emote(CommandContext<CommandSourceStack> ctx, int angle) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayerOrException();
        if ("wheel".equals(StringArgumentType.getString(ctx, "emote"))) {          // the wheel opened on the player's screen
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.PlayerEmotePayload(p.getId(), -1, 0));
            return 1;
        }
        Act a = Act.named(StringArgumentType.getString(ctx, "emote"));
        com.jrpetty.mcassistant.entity.PlayerEmotes.Result r = a == null ? com.jrpetty.mcassistant.entity.PlayerEmotes.Result.NOT_AN_EMOTE
            : com.jrpetty.mcassistant.entity.PlayerEmotes.emote(p, a.ordinal(), -1, angle);
        if (r != com.jrpetty.mcassistant.entity.PlayerEmotes.Result.OK) {
            ctx.getSource().sendFailure(Component.literal(r == com.jrpetty.mcassistant.entity.PlayerEmotes.Result.TOO_SOON ? "Not so fast."
                : r == com.jrpetty.mcassistant.entity.PlayerEmotes.Result.NOT_NOW ? "Not now." : "No such emote."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("emote: " + a.word()), false);
        return 1;
    }

    private static int befriend(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayerOrException();
        VillageFolkEntity f = nearest(ctx);
        if (f == null) return 0;
        f.ensurePersona();
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 70);
        ctx.getSource().sendSuccess(() -> Component.literal(f.displayNameCap() + " thinks well of you: " + f.persona().affinity(p.getUUID())), false);
        return 1;
    }

    private static int friend(CommandContext<CommandSourceStack> ctx, boolean front) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayerOrException();
        Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 side = new Vec3(-look.z, 0, look.x);
        // Beside it (the turned camera sees both), or face to face at arm's length (for a handshake, side on).
        Vec3 at = front ? p.position().add(look.scale(1.45)) : p.position().add(look.scale(0.2)).add(side.scale(1.9));
        VillageFolkEntity f = AnimStage.friend(ctx.getSource().getLevel(), at.x, p.getY(), at.z, p);
        if (f == null) return 0;
        ctx.getSource().sendSuccess(() -> Component.literal(f.displayNameCap() + " is glad to see you"), false);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = AnimStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }
}
