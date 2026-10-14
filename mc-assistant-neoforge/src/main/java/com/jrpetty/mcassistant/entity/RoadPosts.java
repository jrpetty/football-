package com.jrpetty.mcassistant.entity;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [roles-e] The roads' posts and the hall's, where the rest of the game meets them: the talk (the menu's buttons,
 * client/TalkMenu, and the same words typed), the talk screen's context flags, the folk's card, and "/village roads"
 * (the wheelwright and its order, the carter and its runs, the town planner, the watch's ranks), with "stage" for the
 * pictures (RoadsStage).
 */
public final class RoadPosts {

    private RoadPosts() {}

    /** [FolkTalk.talkContext] The posts this folk holds, as the talk screen's flags: " wheelwright carter planner". */
    public static String contextFlags(VillageFolkEntity f) {
        if (f.ownerId() == null) return "";
        StringBuilder sb = new StringBuilder();
        if (Wheelwrights.is(f)) sb.append(" wheelwright");
        if (Carters.is(f)) sb.append(" carter");
        if (TownPlanner.is(f)) sb.append(" planner");
        return sb.toString();
    }

    /**
     * [FolkTalk.answer] Words about the town's wagon, its road or its plans, said to anybody grown of a town: the post's
     * holder answers from its own work, anybody else points to it. Null when the words ask none of that.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (f.isBaby() || f.ownerId() == null || text == null || text.isEmpty() || !(f.level() instanceof ServerLevel level)) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (Carters.meant(lower)) return Carters.talk(f, level);
        if (Wheelwrights.meant(lower)) return Wheelwrights.talk(f, level);
        if (TownPlanner.meant(lower)) return TownPlanner.talk(f);
        return null;
    }

    /** [FolkTalk.card] Its post, if it holds one of these. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby() || f.ownerId() == null) return "";
        List<String> parts = new ArrayList<>();
        for (String s : new String[]{ Wheelwrights.cardLine(f), Carters.cardLine(f), TownPlanner.cardLine(f) }) if (!s.isEmpty()) parts.add(s);
        return String.join("; ", parts);
    }

    /** [Shifts.talk] A post's own paid shift, offered by its holder whatever its trade: the wheelwright's hand. */
    static boolean bosses(VillageFolkEntity f, ShiftRole r) {
        return r == ShiftRole.WAGON && Wheelwrights.is(f);
    }

    /** "/village roads": the wheelwright, the carter, the planner and the watch's ranks, in words. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("ROADS AND POSTS in " + Villages.name(id) + ":");
        out.addAll(Wheelwrights.lines(level, v));
        out.addAll(Carters.lines(v));
        out.addAll(TownPlanner.lines(id));
        String sergeants = WatchRanks.names(id);
        int guards = Patrols.watch(id).size();
        out.add("The watch: " + guards + (guards == 1 ? " guard" : " guards") + ", captain "
            + (Police.town(id).getString("captainName").isEmpty() ? "none yet" : Police.town(id).getString("captainName"))
            + "; " + (sergeants.isEmpty() ? "no sergeants (one for every " + WatchRanks.PER_SERGEANT + " guards)" : "sergeants " + sergeants));
        return out;
    }

    // ------------------------------------------------------------------ for the tests

    /** Tests: those who would stand for one of these posts at interview ("wheelwright", "carter", "planner"). */
    public static List<VillageFolkEntity> candidatesForTests(ServerLevel level, Villages.Village v, String post) {
        InterviewPosts.Post p = InterviewPosts.Post.of(post);
        return p == null || !InterviewPosts.stands(level, v, p) ? List.of() : InterviewPosts.candidates(level, v, p, null, false, java.util.Set.of());
    }

    /** Tests: the panel's choice for one of these posts, as an interview decides it (its books, then the post given). */
    public static String chooseForTests(ServerLevel level, Villages.Village v, String post, VillageFolkEntity winner) {
        InterviewPosts.Post p = InterviewPosts.Post.of(post);
        if (p == null) return "";
        InterviewBook.town(v.id()).held.put(post, new String[]{ winner.getStringUUID(), winner.displayNameCap(),
            Long.toString(level.getDayTime() / 24000L) });
        return InterviewPosts.give(level, v, p, winner);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("roads")
            .then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(ctx -> {
                List<String> lines = RoadsStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            }))
            .executes(ctx -> {
                ServerLevel level = ctx.getSource().getLevel();
                Villages.Village v = PlayerServices.townAt(level, BlockPos.containing(ctx.getSource().getPosition()));
                if (v == null) {
                    ctx.getSource().sendFailure(Component.literal("There's no village here."));
                    return 0;
                }
                List<String> lines = lines(level, v);
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            });
    }
}
